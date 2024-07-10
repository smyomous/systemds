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

package org.apache.sysds.runtime.compress.cocode;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.runtime.compress.CompressionSettings;
import org.apache.sysds.runtime.compress.DMLCompressionException;
import org.apache.sysds.runtime.compress.colgroup.AColGroup.CompressionType;
import org.apache.sysds.runtime.compress.colgroup.indexes.ColIndexFactory;
import org.apache.sysds.runtime.compress.colgroup.indexes.IColIndex;
import org.apache.sysds.runtime.compress.cost.ACostEstimate;
import org.apache.sysds.runtime.compress.estim.AComEst;
import org.apache.sysds.runtime.compress.estim.CompressedSizeInfo;
import org.apache.sysds.runtime.compress.estim.CompressedSizeInfoColGroup;

public interface CoCoderFactory {
	public static final Log LOG = LogFactory.getLog(AColumnCoCoder.class.getName());

	/**
	 * The Valid coCoding techniques
	 */
	public enum PartitionerType {
		BIN_PACKING, STATIC, PRIORITY_QUE, GREEDY, AUTO;
	}

	/**
	 * Main entry point of CoCode.
	 * 
	 * This package groups together ColGroups across columns, to improve compression further,
	 * 
	 * @param est           The size estimator used for estimating ColGroups potential sizes and construct compression
	 *                      info objects
	 * @param colInfos      The information already gathered on the individual ColGroups of columns.
	 * @param k             The concurrency degree allowed for this operation.
	 * @param costEstimator The Cost estimator to estimate the cost of the compression
	 * @param cs            The compression settings used in the compression.
	 * @param detectOneHotEncoding            Flag to Enable/Disable OHE Detection
	 * @return The estimated (hopefully) best groups of ColGroups.
	 */
	public static CompressedSizeInfo findCoCodesByPartitioning(AComEst est, CompressedSizeInfo colInfos, int k,
		ACostEstimate costEstimator, CompressionSettings cs, boolean detectOneHotEncoding) {

		detectOneHotEncoding=false;
		

		// Use column group partitioner to create partitions of columns
		AColumnCoCoder co = createColumnGroupPartitioner(cs.columnPartitioner, est, costEstimator, cs);

        boolean containsEmptyConstOrIncompressable = containsEmptyConstOrIncompressable(colInfos);
        
		// If no empty, constant, incompressible groups and not OHE, cocode all columns
        if (!containsEmptyConstOrIncompressable && !detectOneHotEncoding) {
            return co.coCodeColumns(colInfos, k);
		}
		else {
			
			// filtered empty groups
			final List<IColIndex> emptyCols = new ArrayList<>();
			// filtered const groups
			final List<IColIndex> constCols = new ArrayList<>();
			// incompressable groups
			final List<IColIndex> incompressable = new ArrayList<>();
			// filtered groups -- in the end starting with all groups
			final List<CompressedSizeInfoColGroup> groups = new ArrayList<>();

			final int nRow = colInfos.compressionInfo.get(0).getNumRows();

			// filter groups
			List<CompressedSizeInfoColGroup> currentCandidates = new ArrayList<>();
        	List<List<CompressedSizeInfoColGroup>> oheGroups = new ArrayList<>();
			boolean isSample=false;
			if(est.getClass().getSimpleName().equals("ComEstSample")){
				isSample=true;
				LOG.info("isSampleTrue");
			}

			for(int i = 0; i < colInfos.compressionInfo.size(); i++) {
				CompressedSizeInfoColGroup g = colInfos.compressionInfo.get(i);
				if(g.isEmpty())
					emptyCols.add(g.getColumns());
				else if(g.isConst())
					constCols.add(g.getColumns());
				else if(g.isIncompressable())
					incompressable.add(g.getColumns());
				else if (isCandidate(g)) {
                    currentCandidates.add(g);
                    if (isHotEncoded(currentCandidates, isSample)) {
                        oheGroups.add(new ArrayList<>(currentCandidates));
                        currentCandidates.clear();
                    }
                } else {
					groups.add(g);
                    if (!currentCandidates.isEmpty()) {
                        for(CompressedSizeInfoColGroup gg: currentCandidates)
							groups.add(gg);
						currentCandidates.clear();
                    }
                }
			}

			// If currentCandidates is not empty, add it to groups
			if (!currentCandidates.isEmpty()) { 
				for (CompressedSizeInfoColGroup gg : currentCandidates) {
					groups.add(gg);
				}
				currentCandidates.clear();
			}

			// overwrite groups.
			colInfos.compressionInfo = groups;

			for (List<CompressedSizeInfoColGroup> oheGroup : oheGroups) {
                final List<IColIndex> oheIndexes = new ArrayList<>();
                for (CompressedSizeInfoColGroup g : oheGroup) {
                    oheIndexes.add(g.getColumns());
                }
                final IColIndex idx = ColIndexFactory.combineIndexes(oheIndexes);
				groups.add(new CompressedSizeInfoColGroup(idx, nRow, CompressionType.OHE));
                // colInfos.compressionInfo.add(new CompressedSizeInfoColGroup(idx, nRow, CompressionType.OHE));
            }

			// cocode remaining groups
			if(colInfos.getInfo().size()<=0 && incompressable.size()<=0 && emptyCols.size()<=0 && constCols.size()==0 && oheGroups.size()<=0)
			// Check why it's not added back
			// Parsing out statistics to check the performance and if the sizes are smaller
			//  
				throw new DMLCompressionException("empty cocoders 1");
			
			if(!groups.isEmpty()) {
				colInfos = co.coCodeColumns(colInfos, k);
			}

			// add empty
			if(emptyCols.size() > 0) {
				final IColIndex idx = ColIndexFactory.combineIndexes(emptyCols);
				colInfos.compressionInfo.add(new CompressedSizeInfoColGroup(idx, nRow, CompressionType.EMPTY));
			}

			// add const
			if(constCols.size() > 0) {
				final IColIndex idx = ColIndexFactory.combineIndexes(constCols);
				colInfos.compressionInfo.add(new CompressedSizeInfoColGroup(idx, nRow, CompressionType.CONST));
			}

			if(incompressable.size() > 0) {
				final IColIndex idx = ColIndexFactory.combineIndexes(incompressable);
				colInfos.compressionInfo.add(new CompressedSizeInfoColGroup(idx, nRow, CompressionType.UNCOMPRESSED));
			}

			if(colInfos.getInfo().size()<=0)
				throw new DMLCompressionException("empty cocoders 2");
			
			return colInfos;

		}

	}

	private static boolean containsEmptyConstOrIncompressable(CompressedSizeInfo colInfos) {
		for(CompressedSizeInfoColGroup g : colInfos.compressionInfo)
			if(g.isEmpty() || g.isConst() || g.isIncompressable())
				return true;
		return false;
	}

	private static boolean isCandidate(CompressedSizeInfoColGroup g) {
		// Check if the column has exactly 2 distinct value other than 0
		LOG.info(g.getNumVals() + "-" + g.getNumRows() + "-" + g.getNumOffs());
		return g.getNumVals() == 2;
	}

	private static boolean isHotEncoded(List<CompressedSizeInfoColGroup> colGroups, boolean isSample) {
		if (colGroups.isEmpty()) {
			return false;
		}
	
		int numCols = colGroups.size();
		LOG.info("numCols: " + numCols);
		int totalNumVals = 0;
		int totalNumOffs = 0;
		int numRows = colGroups.get(0).getNumRows();
	
		for (CompressedSizeInfoColGroup g : colGroups) {
			totalNumVals += g.getNumVals();
			totalNumOffs += g.getNumOffs();
		}

		LOG.info("totalOffs: " + totalNumOffs + ", totalNumVals: " + totalNumVals);
		double margin = isSample ? 0.0007 : 0.0;
		if (totalNumVals / 2 != numCols || Math.abs(totalNumOffs - numRows) > numRows * margin) {
			return false;
		}
		// if (totalNumVals / 2 != numCols || totalNumOffs != numRows) {
		// 	return false;
		// }
	
		LOG.info("ColGroup is OHE");
		return true;
	}

	private static AColumnCoCoder createColumnGroupPartitioner(PartitionerType type, AComEst est,
		ACostEstimate costEstimator, CompressionSettings cs) {
		switch(type) {
			case AUTO:
				return new CoCodeHybrid(est, costEstimator, cs);
			case GREEDY:
				return new CoCodeGreedy(est, costEstimator, cs);
			case BIN_PACKING:
				return new CoCodeBinPacking(est, costEstimator, cs);
			case STATIC:
				return new CoCodeStatic(est, costEstimator, cs);
			case PRIORITY_QUE:
				return new CoCodePriorityQue(est, costEstimator, cs, 128);
			default:
				throw new RuntimeException("Unsupported column group partition technique: " + type.toString());
		}
	}
}
