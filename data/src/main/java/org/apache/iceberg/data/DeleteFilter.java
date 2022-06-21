/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.iceberg.Accessor;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileContent;
import org.apache.iceberg.MetadataColumns;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.data.avro.DataReader;
import org.apache.iceberg.data.orc.GenericOrcReader;
import org.apache.iceberg.data.parquet.GenericParquetReaders;
import org.apache.iceberg.deletes.Deletes;
import org.apache.iceberg.deletes.PositionDeleteIndex;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.expressions.Literal;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.orc.ORC;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.parquet.ParquetBloomRowGroupFilter;
import org.apache.iceberg.parquet.ParquetUtil;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.Iterables;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.relocated.com.google.common.collect.Multimap;
import org.apache.iceberg.relocated.com.google.common.collect.Multimaps;
import org.apache.iceberg.relocated.com.google.common.collect.Sets;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.StructLikeSet;
import org.apache.iceberg.util.StructProjection;
import org.apache.parquet.hadoop.BloomFilterReader;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.schema.MessageType;

public abstract class DeleteFilter<T> {
  private static final long DEFAULT_SET_FILTER_THRESHOLD = 100_000L;
  private static final Schema POS_DELETE_SCHEMA = new Schema(
      MetadataColumns.DELETE_FILE_PATH,
      MetadataColumns.DELETE_FILE_POS);

  private final long setFilterThreshold;
  private final String filePath;
  private final List<DeleteFile> posDeletes;
  private final List<DeleteFile> eqDeletes;
  private final Schema requiredSchema;
  private final Accessor<StructLike> posAccessor;
  private final boolean hasIsDeletedColumn;
  private final int isDeletedColumnPosition;

  private PositionDeleteIndex deleteRowPositions = null;
  private Predicate<T> eqDeleteRows = null;

  protected DeleteFilter(String filePath, List<DeleteFile> deletes, Schema tableSchema, Schema requestedSchema) {
    this.setFilterThreshold = DEFAULT_SET_FILTER_THRESHOLD;
    this.filePath = filePath;

    ImmutableList.Builder<DeleteFile> posDeleteBuilder = ImmutableList.builder();
    ImmutableList.Builder<DeleteFile> eqDeleteBuilder = ImmutableList.builder();
    for (DeleteFile delete : deletes) {
      switch (delete.content()) {
        case POSITION_DELETES:
          posDeleteBuilder.add(delete);
          break;
        case EQUALITY_DELETES:
          eqDeleteBuilder.add(delete);
          break;
        default:
          throw new UnsupportedOperationException("Unknown delete file content: " + delete.content());
      }
    }

    this.posDeletes = posDeleteBuilder.build();
    this.eqDeletes = eqDeleteBuilder.build();
    this.requiredSchema = fileProjection(tableSchema, requestedSchema, posDeletes, eqDeletes);
    this.posAccessor = requiredSchema.accessorForField(MetadataColumns.ROW_POSITION.fieldId());
    this.hasIsDeletedColumn = requiredSchema.findField(MetadataColumns.IS_DELETED.fieldId()) != null;
    this.isDeletedColumnPosition = requiredSchema.columns().indexOf(MetadataColumns.IS_DELETED);
  }

  protected int columnIsDeletedPosition() {
    return isDeletedColumnPosition;
  }

  public Schema requiredSchema() {
    return requiredSchema;
  }

  public boolean hasPosDeletes() {
    return !posDeletes.isEmpty();
  }

  public boolean hasEqDeletes() {
    return !eqDeletes.isEmpty();
  }

  Accessor<StructLike> posAccessor() {
    return posAccessor;
  }

  protected abstract StructLike asStructLike(T record);

  protected abstract InputFile getInputFile(String location);

  protected long pos(T record) {
    return (Long) posAccessor.get(asStructLike(record));
  }

  public CloseableIterable<T> filter(CloseableIterable<T> records) {
    return applyEqDeletes(applyPosDeletes(records));
  }

  private List<Predicate<T>> applyEqDeletes() {
    List<Predicate<T>> isInDeleteSets = Lists.newArrayList();
    Map<BlockMetaData, BloomFilterReader> parquetBloomFilterReader = Maps.newHashMap();
    ParquetFileReader parquetReader = null;
    Predicate<Record> isInBloomFilter = null;
    if (eqDeletes.isEmpty()) {
      return isInDeleteSets;
    }

    // load bloomfilter readers from data file
    if (filePath.endsWith(".parquet")) {
      parquetReader = ParquetUtil.openFile(getInputFile(filePath));
      parquetBloomFilterReader.putAll(ParquetUtil.getParquetBloomFilters(parquetReader));
    }

    Multimap<Set<Integer>, DeleteFile> filesByDeleteIds = Multimaps.newMultimap(Maps.newHashMap(), Lists::newArrayList);
    for (DeleteFile delete : eqDeletes) {
      filesByDeleteIds.put(Sets.newHashSet(delete.equalityFieldIds()), delete);
    }

    for (Map.Entry<Set<Integer>, Collection<DeleteFile>> entry : filesByDeleteIds.asMap().entrySet()) {
      Set<Integer> ids = entry.getKey();
      Iterable<DeleteFile> deletes = entry.getValue();

      Schema deleteSchema = TypeUtil.select(requiredSchema, ids);

      if (filePath.endsWith(".parquet") && parquetReader != null) {
        MessageType fileSchema = parquetReader.getFileMetaData().getSchema();
        isInBloomFilter =
                record -> findInParquetBloomFilter(record, deleteSchema, fileSchema, parquetBloomFilterReader);
      }

      // a projection to select and reorder fields of the file schema to match the delete rows
      StructProjection projectRow = StructProjection.create(requiredSchema, deleteSchema);

      Iterable<CloseableIterable<Record>> deleteRecords = Iterables.transform(deletes,
          delete -> openDeletes(delete, deleteSchema));

      // copy the delete records because they will be held in a set
      CloseableIterable<Record> records = CloseableIterable.transform(
          CloseableIterable.concat(deleteRecords), Record::copy);

      // apply bloomfilter on delete records
      if (isInBloomFilter != null) {
        records = CloseableIterable.filter(records, isInBloomFilter);
      }

      StructLikeSet deleteSet = Deletes.toEqualitySet(
          CloseableIterable.transform(
              records, record -> new InternalRecordWrapper(deleteSchema.asStruct()).wrap(record)),
          deleteSchema.asStruct());

      Predicate<T> isInDeleteSet = record -> deleteSet.contains(projectRow.wrap(asStructLike(record)));
      isInDeleteSets.add(isInDeleteSet);
    }
    try {
      if (parquetReader != null) {
        parquetReader.close();
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed to close parquet file reader!", e);
    }
    return isInDeleteSets;
  }

  private boolean findInParquetBloomFilter(
          Record record,
          Schema deleteSchema,
          MessageType fileSchema,
          Map<BlockMetaData, BloomFilterReader> parquetBloomFilterReader) {
    if (record.size() == 0 || parquetBloomFilterReader.isEmpty()) {
      return true;
    }
    // build filter by record values
    Expression filter = buildFilter(record, deleteSchema);
    ParquetBloomRowGroupFilter bloomFilter = new ParquetBloomRowGroupFilter(deleteSchema, filter, true);
    for (Map.Entry<BlockMetaData, BloomFilterReader> entry : parquetBloomFilterReader.entrySet()) {
      boolean shouldRead = bloomFilter.shouldRead(fileSchema, entry.getKey(), entry.getValue());
      if (shouldRead) {
        return true;
      }
    }

    return false;
  }

  private Expression buildFilter(Record record, Schema schema) {
    Expression filter = Expressions.alwaysTrue();
    for (Types.NestedField field : schema.columns()) {
      Object value = getRecordValue(record, field);
      if (value == null) {
        continue;
      }
      filter = Expressions.and(filter, Expressions.equal(field.name(), value));
    }
    return filter;
  }

  private Object getRecordValue(Record record, Types.NestedField field) {
    Type type = field.type();
    switch (type.toString()) {
      case "date":
        return Literal.of(record.getField(field.name()).toString()).to(Types.DateType.get()).value();
      case "time":
        return Literal.of(record.getField(field.name()).toString()).to(Types.TimeType.get()).value();
      case "timestamp":
        if (((Types.TimestampType) type).shouldAdjustToUTC()) {
          return Literal.of(record.getField(field.name()).toString()).to(Types.TimestampType.withZone()).value();
        } else {
          return Literal.of(record.getField(field.name()).toString()).to(Types.TimestampType.withoutZone()).value();
        }
      default:
        return record.getField(field.name());
    }
  }

  public CloseableIterable<T> findEqualityDeleteRows(CloseableIterable<T> records) {
    // Predicate to test whether a row has been deleted by equality deletions.
    Predicate<T> deletedRows = applyEqDeletes().stream()
        .reduce(Predicate::or)
        .orElse(t -> false);

    return CloseableIterable.filter(records, deletedRows);
  }

  private CloseableIterable<T> applyEqDeletes(CloseableIterable<T> records) {
    Predicate<T> isEqDeleted = applyEqDeletes().stream()
        .reduce(Predicate::or)
        .orElse(t -> false);

    return createDeleteIterable(records, isEqDeleted);
  }

  protected void markRowDeleted(T item) {
    throw new UnsupportedOperationException(this.getClass().getName() + " does not implement markRowDeleted");
  }

  public Predicate<T> eqDeletedRowFilter() {
    if (eqDeleteRows == null) {
      eqDeleteRows = applyEqDeletes().stream()
          .map(Predicate::negate)
          .reduce(Predicate::and)
          .orElse(t -> true);
    }
    return eqDeleteRows;
  }

  public PositionDeleteIndex deletedRowPositions() {
    if (posDeletes.isEmpty()) {
      return null;
    }

    if (deleteRowPositions == null) {
      List<CloseableIterable<Record>> deletes = Lists.transform(posDeletes, this::openPosDeletes);
      deleteRowPositions = Deletes.toPositionIndex(filePath, deletes);
    }
    return deleteRowPositions;
  }

  private CloseableIterable<T> applyPosDeletes(CloseableIterable<T> records) {
    if (posDeletes.isEmpty()) {
      return records;
    }

    List<CloseableIterable<Record>> deletes = Lists.transform(posDeletes, this::openPosDeletes);

    // if there are fewer deletes than a reasonable number to keep in memory, use a set
    if (posDeletes.stream().mapToLong(DeleteFile::recordCount).sum() < setFilterThreshold) {
      PositionDeleteIndex positionIndex = Deletes.toPositionIndex(filePath, deletes);
      Predicate<T> isDeleted = record -> positionIndex.isDeleted(pos(record));
      return createDeleteIterable(records, isDeleted);
    }

    return hasIsDeletedColumn ?
        Deletes.streamingMarker(records, this::pos, Deletes.deletePositions(filePath, deletes), this::markRowDeleted) :
        Deletes.streamingFilter(records, this::pos, Deletes.deletePositions(filePath, deletes));
  }

  private CloseableIterable<T> createDeleteIterable(CloseableIterable<T> records, Predicate<T> isDeleted) {
    return hasIsDeletedColumn ?
        Deletes.markDeleted(records, isDeleted, this::markRowDeleted) :
        Deletes.filterDeleted(records, isDeleted);
  }

  private CloseableIterable<Record> openPosDeletes(DeleteFile file) {
    return openDeletes(file, POS_DELETE_SCHEMA);
  }

  private CloseableIterable<Record> openDeletes(DeleteFile deleteFile, Schema deleteSchema) {
    InputFile input = getInputFile(deleteFile.path().toString());
    switch (deleteFile.format()) {
      case AVRO:
        return Avro.read(input)
            .project(deleteSchema)
            .reuseContainers()
            .createReaderFunc(DataReader::create)
            .build();

      case PARQUET:
        Parquet.ReadBuilder builder = Parquet.read(input)
            .project(deleteSchema)
            .reuseContainers()
            .createReaderFunc(fileSchema -> GenericParquetReaders.buildReader(deleteSchema, fileSchema));

        if (deleteFile.content() == FileContent.POSITION_DELETES) {
          builder.filter(Expressions.equal(MetadataColumns.DELETE_FILE_PATH.name(), filePath));
        }

        return builder.build();

      case ORC:
        // Reusing containers is automatic for ORC. No need to set 'reuseContainers' here.
        ORC.ReadBuilder orcBuilder = ORC.read(input)
            .project(deleteSchema)
            .createReaderFunc(fileSchema -> GenericOrcReader.buildReader(deleteSchema, fileSchema));

        if (deleteFile.content() == FileContent.POSITION_DELETES) {
          orcBuilder.filter(Expressions.equal(MetadataColumns.DELETE_FILE_PATH.name(), filePath));
        }

        return orcBuilder.build();
      default:
        throw new UnsupportedOperationException(String.format(
            "Cannot read deletes, %s is not a supported format: %s", deleteFile.format().name(), deleteFile.path()));
    }
  }

  private static Schema fileProjection(Schema tableSchema, Schema requestedSchema,
                                       List<DeleteFile> posDeletes, List<DeleteFile> eqDeletes) {
    if (posDeletes.isEmpty() && eqDeletes.isEmpty()) {
      return requestedSchema;
    }

    Set<Integer> requiredIds = Sets.newLinkedHashSet();
    if (!posDeletes.isEmpty()) {
      requiredIds.add(MetadataColumns.ROW_POSITION.fieldId());
    }

    for (DeleteFile eqDelete : eqDeletes) {
      requiredIds.addAll(eqDelete.equalityFieldIds());
    }

    Set<Integer> missingIds = Sets.newLinkedHashSet(
        Sets.difference(requiredIds, TypeUtil.getProjectedIds(requestedSchema)));

    if (missingIds.isEmpty()) {
      return requestedSchema;
    }

    // TODO: support adding nested columns. this will currently fail when finding nested columns to add
    List<Types.NestedField> columns = Lists.newArrayList(requestedSchema.columns());
    for (int fieldId : missingIds) {
      if (fieldId == MetadataColumns.ROW_POSITION.fieldId() || fieldId == MetadataColumns.IS_DELETED.fieldId()) {
        continue; // add _pos and _deleted at the end
      }

      Types.NestedField field = tableSchema.asStruct().field(fieldId);
      Preconditions.checkArgument(field != null, "Cannot find required field for ID %s", fieldId);

      columns.add(field);
    }

    if (missingIds.contains(MetadataColumns.ROW_POSITION.fieldId())) {
      columns.add(MetadataColumns.ROW_POSITION);
    }

    if (missingIds.contains(MetadataColumns.IS_DELETED.fieldId())) {
      columns.add(MetadataColumns.IS_DELETED);
    }

    return new Schema(columns);
  }
}
