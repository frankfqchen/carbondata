/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.carbondata.integration.spark.merger;

import java.io.File;
import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.carbondata.common.logging.LogService;
import org.carbondata.common.logging.LogServiceFactory;
import org.carbondata.core.carbon.CarbonTableIdentifier;
import org.carbondata.core.carbon.datastore.block.SegmentProperties;
import org.carbondata.core.carbon.metadata.CarbonMetadata;
import org.carbondata.core.carbon.metadata.schema.table.CarbonTable;
import org.carbondata.core.carbon.metadata.schema.table.column.CarbonMeasure;
import org.carbondata.core.carbon.path.CarbonStorePath;
import org.carbondata.core.carbon.path.CarbonTablePath;
import org.carbondata.core.constants.CarbonCommonConstants;
import org.carbondata.core.keygenerator.KeyGenException;
import org.carbondata.core.util.ByteUtil;
import org.carbondata.core.util.CarbonUtil;
import org.carbondata.core.util.DataTypeUtil;
import org.carbondata.core.vo.ColumnGroupModel;
import org.carbondata.processing.datatypes.GenericDataType;
import org.carbondata.processing.merger.exeception.SliceMergerException;
import org.carbondata.processing.store.CarbonDataFileAttributes;
import org.carbondata.processing.store.CarbonFactDataHandlerColumnar;
import org.carbondata.processing.store.CarbonFactDataHandlerModel;
import org.carbondata.processing.store.CarbonFactHandler;
import org.carbondata.processing.store.writer.exception.CarbonDataWriterException;
import org.carbondata.query.carbon.result.iterator.RawResultIterator;
import org.carbondata.query.carbon.wrappers.ByteArrayWrapper;
import org.carbondata.spark.load.CarbonLoadModel;

/**
 * This is the Merger class responsible for the merging of the segments.
 */
public class RowResultMerger {

  private final String schemaName;
  private final String tableName;
  private final String tempStoreLocation;
  private final int measureCount;
  private final String factStoreLocation;
  private CarbonFactHandler dataHandler;
  private List<RawResultIterator> rawResultIteratorList =
      new ArrayList<RawResultIterator>(CarbonCommonConstants.DEFAULT_COLLECTION_SIZE);
  private SegmentProperties segprop;
  /**
   * record holder heap
   */
  private AbstractQueue<RawResultIterator> recordHolderHeap;

  private TupleConversionAdapter tupleConvertor;
  private ColumnGroupModel colGrpStoreModel;

  private static final LogService LOGGER =
      LogServiceFactory.getLogService(RowResultMerger.class.getName());

  public RowResultMerger(List<RawResultIterator> iteratorList,
      String schemaName, String tableName,
      SegmentProperties segProp, String tempStoreLocation,
      CarbonLoadModel loadModel, int[] colCardinality) {

    this.rawResultIteratorList = iteratorList;
    // create the List of RawResultIterator.

    recordHolderHeap = new PriorityQueue<RawResultIterator>(rawResultIteratorList.size(),
        new RowResultMerger.CarbonMdkeyComparator());

    this.segprop = segProp;
    this.tempStoreLocation = tempStoreLocation;

    this.factStoreLocation = loadModel.getStorePath();

    if (!new File(tempStoreLocation).mkdirs()) {
      LOGGER.error("Error while new File(tempStoreLocation).mkdirs() ");
    }

    this.schemaName = schemaName;
    this.tableName = tableName;

    this.measureCount = segprop.getMeasures().size();

    CarbonFactDataHandlerModel carbonFactDataHandlerModel =
        getCarbonFactDataHandlerModel(loadModel);
    carbonFactDataHandlerModel.setPrimitiveDimLens(segprop.getDimColumnsCardinality());
    CarbonDataFileAttributes carbonDataFileAttributes =
        new CarbonDataFileAttributes(Integer.parseInt(loadModel.getTaskNo()),
            loadModel.getFactTimeStamp());
    carbonFactDataHandlerModel.setCarbonDataFileAttributes(carbonDataFileAttributes);
    if (segProp.getNumberOfNoDictionaryDimension() > 0
        || segProp.getComplexDimensions().size() > 0) {
      carbonFactDataHandlerModel.setMdKeyIndex(measureCount + 1);
    } else {
      carbonFactDataHandlerModel.setMdKeyIndex(measureCount);
    }
    carbonFactDataHandlerModel.setColCardinality(colCardinality);

    dataHandler = new CarbonFactDataHandlerColumnar(carbonFactDataHandlerModel);

    tupleConvertor = new TupleConversionAdapter(segProp);
  }

  /**
   * Merge function
   *
   * @throws SliceMergerException
   */
  public boolean mergerSlice() throws SliceMergerException {
    boolean mergeStatus = false;
    int index = 0;
    try {

      dataHandler.initialise();

      // add all iterators to the queue
      for (RawResultIterator leaftTupleIterator : this.rawResultIteratorList) {
        this.recordHolderHeap.add(leaftTupleIterator);
        index++;
      }
      RawResultIterator iterator = null;
      while (index > 1) {
        // iterator the top record
        iterator = this.recordHolderHeap.poll();
        Object[] convertedRow = iterator.next();
        if(null == convertedRow){
          throw new SliceMergerException("Unable to generate mdkey during compaction.");
        }
        // get the mdkey
        addRow(convertedRow);
        // if there is no record in the leaf and all then decrement the
        // index
        if (!iterator.hasNext()) {
          index--;
          continue;
        }
        // add record to heap
        this.recordHolderHeap.add(iterator);
      }
      // if record holder is not empty then iterator the slice holder from
      // heap
      iterator = this.recordHolderHeap.poll();
      while (true) {
        Object[] convertedRow = iterator.next();
        if(null == convertedRow){
          throw new SliceMergerException("Unable to generate mdkey during compaction.");
        }
        addRow(convertedRow);
        // check if leaf contains no record
        if (!iterator.hasNext()) {
          break;
        }
      }
      this.dataHandler.finish();

    } catch (CarbonDataWriterException e) {
      return mergeStatus;
    } finally {
      try {
        this.dataHandler.closeHandler();
      } catch (CarbonDataWriterException e) {
        return false;
      }
    }
    return true;
  }

  /**
   * Below method will be used to add sorted row
   *
   * @throws SliceMergerException
   */
  protected void addRow(Object[] carbonTuple) throws SliceMergerException {
    Object[] rowInWritableFormat;

    rowInWritableFormat = tupleConvertor.getObjectArray(carbonTuple);
    try {
      this.dataHandler.addDataToStore(rowInWritableFormat);
    } catch (CarbonDataWriterException e) {
      throw new SliceMergerException("Problem in merging the slice", e);
    }
  }

  /**
   * This method will create a model object for carbon fact data handler
   *
   * @param loadModel
   * @return
   */
  private CarbonFactDataHandlerModel getCarbonFactDataHandlerModel(CarbonLoadModel loadModel) {
    CarbonFactDataHandlerModel carbonFactDataHandlerModel = new CarbonFactDataHandlerModel();
    carbonFactDataHandlerModel.setDatabaseName(schemaName);
    carbonFactDataHandlerModel.setTableName(tableName);
    carbonFactDataHandlerModel.setMeasureCount(segprop.getMeasures().size());
    carbonFactDataHandlerModel
        .setMdKeyLength(segprop.getDimensionKeyGenerator().getKeySizeInBytes());
    carbonFactDataHandlerModel.setStoreLocation(tempStoreLocation);
    carbonFactDataHandlerModel.setDimLens(segprop.getDimColumnsCardinality());
    carbonFactDataHandlerModel.setNoDictionaryCount(segprop.getNumberOfNoDictionaryDimension());
    carbonFactDataHandlerModel.setDimensionCount(
        segprop.getDimensions().size() - carbonFactDataHandlerModel.getNoDictionaryCount());
    //TO-DO Need to handle complex types here .
    Map<Integer, GenericDataType> complexIndexMap =
        new HashMap<Integer, GenericDataType>(segprop.getComplexDimensions().size());
    carbonFactDataHandlerModel.setComplexIndexMap(complexIndexMap);
    this.colGrpStoreModel =
        CarbonUtil.getColGroupModel(segprop.getDimColumnsCardinality(), segprop.getColumnGroups());
    carbonFactDataHandlerModel.setColGrpModel(colGrpStoreModel);
    carbonFactDataHandlerModel.setDataWritingRequest(true);

    char[] aggType = new char[segprop.getMeasures().size()];
    Arrays.fill(aggType, 'n');
    int i = 0;
    for (CarbonMeasure msr : segprop.getMeasures()) {
      aggType[i++] = DataTypeUtil.getAggType(msr.getDataType());
    }
    carbonFactDataHandlerModel.setAggType(aggType);
    carbonFactDataHandlerModel.setFactDimLens(segprop.getDimColumnsCardinality());

    String carbonDataDirectoryPath =
        checkAndCreateCarbonStoreLocation(this.factStoreLocation, schemaName, tableName,
            loadModel.getPartitionId(), loadModel.getSegmentId());
    carbonFactDataHandlerModel.setCarbonDataDirectoryPath(carbonDataDirectoryPath);

    return carbonFactDataHandlerModel;
  }

  /**
   * This method will get the store location for the given path, segment id and partition id
   *
   * @return data directory path
   */
  private String checkAndCreateCarbonStoreLocation(String factStoreLocation, String schemaName,
      String tableName, String partitionId, String segmentId) {
    String carbonStorePath = factStoreLocation;
    CarbonTable carbonTable = CarbonMetadata.getInstance()
        .getCarbonTable(schemaName + CarbonCommonConstants.UNDERSCORE + tableName);
    CarbonTableIdentifier carbonTableIdentifier = carbonTable.getCarbonTableIdentifier();
    CarbonTablePath carbonTablePath =
        CarbonStorePath.getCarbonTablePath(carbonStorePath, carbonTableIdentifier);
    String carbonDataDirectoryPath =
        carbonTablePath.getCarbonDataDirectoryPath(partitionId, segmentId);
    CarbonUtil.checkAndCreateFolder(carbonDataDirectoryPath);
    return carbonDataDirectoryPath;
  }

  /**
   * Comparator class for comparing 2 raw row result.
   */
  private class CarbonMdkeyComparator implements Comparator<RawResultIterator> {

    @Override public int compare(RawResultIterator o1, RawResultIterator o2) {

      Object[] row1 = new Object[0];
      Object[] row2 = new Object[0];
      try {
        row1 = o1.fetchConverted();
        row2 = o2.fetchConverted();
      } catch (KeyGenException e) {
        LOGGER.error(e.getMessage());
      }
      if (null == row1 || null == row2) {
        return 0;
      }
      ByteArrayWrapper key1 = (ByteArrayWrapper) row1[0];
      ByteArrayWrapper key2 = (ByteArrayWrapper) row2[0];
      int compareResult = 0;
      int[] columnValueSizes = segprop.getEachDimColumnValueSize();
      int dictionaryKeyOffset = 0;
      byte[] dimCols1 = key1.getDictionaryKey();
      byte[] dimCols2 = key2.getDictionaryKey();
      int noDicIndex = 0;
      for (int eachColumnValueSize : columnValueSizes) {
        // case of dictionary cols
        if (eachColumnValueSize > 0) {

          compareResult = ByteUtil.UnsafeComparer.INSTANCE
              .compareTo(dimCols1, dictionaryKeyOffset, eachColumnValueSize, dimCols2,
                  dictionaryKeyOffset, eachColumnValueSize);
          dictionaryKeyOffset += eachColumnValueSize;
        } else { // case of no dictionary

          byte[] noDictionaryDim1 = key1.getNoDictionaryKeyByIndex(noDicIndex);
          byte[] noDictionaryDim2 = key2.getNoDictionaryKeyByIndex(noDicIndex);
          compareResult =
              ByteUtil.UnsafeComparer.INSTANCE.compareTo(noDictionaryDim1, noDictionaryDim2);
          noDicIndex++;

        }
        if (0 != compareResult) {
          return compareResult;
        }
      }
      return 0;
    }
  }

}
