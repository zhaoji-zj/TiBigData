/*
 * Copyright 2022 TiDB Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.tidb.bigdata.tidb;

import io.tidb.bigdata.tidb.meta.TiIndexColumn;
import io.tidb.bigdata.tidb.meta.TiIndexInfo;
import io.tidb.bigdata.tidb.meta.TiTableInfo;
import io.tidb.bigdata.tidb.row.Row;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.tikv.common.util.Pair;

public abstract class RowBuffer {

  protected final int bufferSize;
  protected List<Row> rows;

  protected RowBuffer(int bufferSize) {
    this.bufferSize = bufferSize;
    this.rows = new ArrayList<>(bufferSize);
  }

  public abstract boolean add(Row row);

  public int size() {
    return rows.size();
  }

  public int addAll(Collection<Row> collection) {
    return (int) collection.stream().map(this::add).filter(b -> b).count();
  }

  public List<Row> getRows() {
    return rows;
  }

  public void clear() {
    this.rows = new ArrayList<>(bufferSize);
  }

  public boolean isFull() {
    return rows.size() == bufferSize;
  }

  public static RowBuffer createDefault(int bufferSize) {
    return new DefaultRowBuffer(bufferSize);
  }

  public static RowBuffer createDeduplicateRowBuffer(
      TiTableInfo tiTableInfo, boolean ignoreAutoincrementColumn, int bufferSize) {
    return new DeduplicateRowBuffer(tiTableInfo, ignoreAutoincrementColumn, bufferSize);
  }

  static class DefaultRowBuffer extends RowBuffer {

    public DefaultRowBuffer(int bufferSize) {
      super(bufferSize);
    }

    @Override
    public boolean add(Row row) {
      if (isFull()) {
        throw new IllegalStateException("Row buffer is full!");
      }
      rows.add(row);
      return true;
    }
  }

  static class DeduplicateRowBuffer extends RowBuffer {

    private final TiTableInfo tiTableInfo;
    // index -> index values
    private final List<Pair<List<Integer>, Set<List<Object>>>> uniqueIndexValues;

    private DeduplicateRowBuffer(
        TiTableInfo tiTableInfo, boolean ignoreAutoincrementColumn, int bufferSize) {
      super(bufferSize);
      this.tiTableInfo = tiTableInfo;
      List<TiIndexInfo> uniqueIndexes =
          SqlUtils.getUniqueIndexes(tiTableInfo, ignoreAutoincrementColumn);
      this.uniqueIndexValues = new ArrayList<>(uniqueIndexes.size());
      for (TiIndexInfo uniqueIndex : uniqueIndexes) {
        List<Integer> columnIndex =
            uniqueIndex.getIndexColumns().stream()
                .map(TiIndexColumn::getOffset)
                .collect(Collectors.toList());
        uniqueIndexValues.add(new Pair<>(columnIndex, new HashSet<>()));
      }
    }

    @Override
    public boolean add(Row row) {
      if (isFull()) {
        throw new IllegalStateException("Row buffer is full!");
      }
      if (uniqueIndexValues.size() == 0) {
        rows.add(row);
        return true;
      }
      List<List<Object>> values = new ArrayList<>();
      for (Pair<List<Integer>, Set<List<Object>>> pair : uniqueIndexValues) {
        List<Integer> indexColumns = pair.first;
        Set<List<Object>> indexValues = pair.second;
        List<Object> indexValue =
            indexColumns.stream().map(i -> row.get(i, null)).collect(Collectors.toList());
        if (indexValues.contains(indexValue)) {
          return false;
        }
        values.add(indexValue);
      }
      // add row
      rows.add(row);
      // add index value
      for (int i = 0; i < uniqueIndexValues.size(); i++) {
        uniqueIndexValues.get(i).second.add(values.get(i));
      }
      return true;
    }

    @Override
    public void clear() {
      super.clear();
      for (int i = 0; i < uniqueIndexValues.size(); i++) {
        Pair<List<Integer>, Set<List<Object>>> oldPair = uniqueIndexValues.get(i);
        uniqueIndexValues.set(i, new Pair<>(oldPair.first, new HashSet<>()));
      }
    }
  }
}
