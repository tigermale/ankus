/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ankus.mapreduce.algorithms.clustering.kmeans;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import org.ankus.mapreduce.algorithms.clustering.common.ClusterCommon;
import org.ankus.mapreduce.algorithms.preprocessing.normalize.NormalizeDriver;
import org.ankus.mapreduce.algorithms.preprocessing.normalize.NormalizeMapper;
import org.ankus.mapreduce.algorithms.statistics.nominalstats.NominalStatsDriver;
import org.ankus.util.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KMeansDriver
 * @desc map/reduce based k-means clustering
 *
 * @version 0.0.1
 * @date : 2013.08.21
 * @author Moonie
 */
public class KMeansDriver extends Configured implements Tool {

    private Logger logger = LoggerFactory.getLogger(KMeansDriver.class);

//	private String mNominalDelimiter = "@@";     // delimiter for nominal attribute in cluster info
	private int mIndexArr[];                      // attribute index array will be used clustering
	private int mNominalIndexArr[];              // nominal attribute index array will be used clustering
	private int mExceptionIndexArr[];           // attribute index array will be not used clustering

    // TODO: convergence rate
    private double mConvergeRate = 0.001;
    private int mIterMax = 100;
	
	@Override
	public int run(String[] args) throws Exception {

        logger.info("K-Means Clustering MR-Job is Started..");

		Configuration conf = this.getConf();
		if(!ConfigurationVariable.setFromArguments(args, conf))
		{
            Usage.printUsage(Constants.ALGORITHM_KMEANS_CLUSTERING);
            logger.info("K-Means Clustering MR-Job is Failed..: Configuration Failed");
            return 1;
		}


        boolean isOnlyTest = false;
        boolean isTrained = false;
        boolean isTrainResultGen = false;
        if(conf.get(ArgumentsConstants.TRAINED_MODEL, null) != null) isOnlyTest = true;

        String outputBase = conf.get(ArgumentsConstants.OUTPUT_PATH, null);
//        String delimiter = conf.get(ArgumentsConstants.DELIMITER, "\t");
        String finalClusterPath = "";

        int iterCnt = 0;

        // training
        if(!isOnlyTest)
        {
            if(conf.get(ArgumentsConstants.NORMALIZE, "true").equals("true"))
            {
                logger.info("Normalization for K-Means is Started..");
                String normalizationJobOutput = outputBase + "/normalize";
                String params[] = getParametersForNormalization(conf, normalizationJobOutput);

                int res = ToolRunner.run(new NormalizeDriver(), params);
                if(res!=0)
                {
                    logger.info("Normalization for K-Means is Failed..");

                    return 1;
                }

                logger.info("Normalization for K-Means is Successfully Finished..");
                conf.set(ArgumentsConstants.INPUT_PATH, normalizationJobOutput);
            }

            /**
             * clustering process
             * 1. set initial cluster center (old-cluster): Main Driver Class
             * 		numeric - distribution based ( min + ((max-min)/clusterCnt*clusterIndex))
             * 		nominal - frequency ratio
             * 2.assign cluster to each data and update cluster center (MR Job)
             * 		2.1 assign cluster to data using cluster-data distance (for all data): Map
             * 		2.2 update cluster center (new-cluster): Reduce
             * 4. compare old-cluster / new-cluster: Main Driver Class
             * 5. decision: Main Driver Class
             * 		if(euqal) exec 2.1 and finish
             * 		else if(maxIter) exec 2.1 and finish
             * 		else
             * 		{
             * 			pre-cluster <= post-cluster
             * 			goto Step 2
             * 		}
             *
             * cluster representation
             * 0. id
             * 1. numeric => index '' value
             * 2. nominal => index '' value@@ratio[@@value@@ratio]+
             *
             * cluster-data distance
             * 1. numeric => clusterVal - dataVal
             * 2. nominal => 1 - ratio(cluster=data)
             * => Total Distance (Euclidean / Manhatan)
             */
            logger.info("Core Part for K-Means is Started..");

            // TODO: convergence rate
            mConvergeRate = Double.parseDouble(conf.get(ArgumentsConstants.CLUSTER_TRAINING_CONVERGE, mConvergeRate + ""));
            setIndexArray(conf);


            String oldClusterPath = outputBase + "/cluster_center_0";
            String newClusterPath;

            logger.info("> Cluster Center Initializing is Started....");
            setInitialClusterCenter(conf, oldClusterPath);							// init cluster

            logger.info("> Cluster Center Initializing is Finished....");
            logger.info("> Iteration(Cluster Assign / Cluster Update) is Started....");
            while(true)
            {
                iterCnt++;
                logger.info("> Iteration: " + iterCnt);

                newClusterPath = outputBase + "/cluster_center_" + iterCnt;
                conf.set(ArgumentsConstants.OUTPUT_PATH, newClusterPath);
                logger.info(">> MR-Job for Cluster Assign / Cluster Update is Started..");
                if(!assignAndResetCluster(conf, oldClusterPath))
                {
                    logger.info(">> MR-Job for Cluster Assign / Cluster Update is Failed..");
                    return 1;			// MR Job, assign and update cluster
                }
                logger.info(">> MR-Job for Cluster Assign / Cluster Update is Finished...");

                logger.info(">> Iteration Break Condition Check..");

                if(isClustersEqual(conf, oldClusterPath, newClusterPath)) break;	// cluster check
                else if(iterCnt >= Integer.parseInt(conf.get(ArgumentsConstants.MAX_ITERATION, mIterMax + ""))) break;

                logger.info(">> Iteration is not Broken. Continue Next Iteration..");
                oldClusterPath = newClusterPath;
            }
            logger.info(">> Iteration is Broken..");
            logger.info("> Iteration(Cluster Assign / Cluster Update) is Finished....");

            isTrained = true;
            if(conf.get(ArgumentsConstants.FINAL_RESULT_GENERATION, "true").equals("true"))
                isTrainResultGen = true;

            finalClusterPath = newClusterPath;
        }

        // testing (model adaptation), final assign
        boolean isOnlyTestNormalize = false;
        if(isOnlyTest || isTrainResultGen)
        {
            if(isTrainResultGen)
            {
                conf.set(ArgumentsConstants.OUTPUT_PATH, outputBase + "/clustering_result");
                if(!finalAssignCluster(conf, finalClusterPath)) return 1;					// Map Job, final assign cluster
                logger.info("> Finish Cluster Assign and Compute Distance for Trained Data..");
            }
            else if(isOnlyTest)
            {
                String trainedModelBaseStr = conf.get(ArgumentsConstants.TRAINED_MODEL, null);
                String clusterCenterPath = "";

                FileSystem fs = FileSystem.get(conf);
                FileStatus[] status = fs.listStatus(new Path(trainedModelBaseStr));
                for (int i=0;i<status.length;i++)
                {
                    if(status[i].getPath().toString().contains("normalize_numericStat")) isOnlyTestNormalize = true;
                    if(status[i].getPath().toString().contains("cluster_center_"))
                    {
                        clusterCenterPath = status[i].getPath().toString();
                    }
                }

                if(isOnlyTestNormalize)
                {
                    String normalisedOutputPath = outputBase + "/normalize";
                    execNormalize(conf, trainedModelBaseStr, normalisedOutputPath);

                    conf.set(ArgumentsConstants.INPUT_PATH, normalisedOutputPath);
                }

                conf.set(ArgumentsConstants.OUTPUT_PATH, outputBase + "/clustering_result");
                conf.set(ArgumentsConstants.CLUSTER_PATH, clusterCenterPath);

                String modelInfoArr[] = getModelInfoStrArr(conf, clusterCenterPath);
                conf.set(ArgumentsConstants.TARGET_INDEX, modelInfoArr[0]);
                conf.set(ArgumentsConstants.NOMINAL_INDEX, modelInfoArr[1]);
                conf.set(ArgumentsConstants.CLUSTER_COUNT, modelInfoArr[2]);

                finalClusterPath = clusterCenterPath;
                if(!finalAssignCluster(conf, finalClusterPath)) return 1;					// Map Job, final assign cluster
                logger.info("> Finish Cluster Assign and Compute Distance for New Data..");

            }

            // TODO: purity gen > nominal stat
            /*
            parameter setting > nominalStatMR
             */

            conf.set(ArgumentsConstants.INPUT_PATH, conf.get(ArgumentsConstants.OUTPUT_PATH));
            conf.set(ArgumentsConstants.OUTPUT_PATH, outputBase + "/purity_mr");
            int clusterIndex = ClusterCommon.getClusterIndex(conf);
            String params[] = ClusterCommon.getParametersForPurity(conf, clusterIndex);

            int res = ToolRunner.run(new NominalStatsDriver(), params);
            if(res!=0)
            {
                logger.info("Purity Computation (Nominal Stats) for K-Means is Failed..");
                return 1;
            }

            // result merge - mr job files
            ClusterCommon.finalPurityGen(conf, outputBase + "/purity");
            FileSystem.get(conf).delete(new Path(conf.get(ArgumentsConstants.OUTPUT_PATH)), true);
            logger.info("Purity Computation (Nominal Stats) for K-Means is Successfully Finished..");
        }


        // temp delete
        if(conf.get(ArgumentsConstants.TEMP_DELETE, "true").equals("true"))
        {
            logger.info("Temporary Files are Deleted..: Cluster Center Info Files");

            if(isTrained)
            {
                for(int i=0; i<iterCnt; i++)
                {
                    FileSystem.get(conf).delete(new Path(outputBase + "/cluster_center_" + i), true);
                }
            }

            //TODO: normalization modify
            if(conf.get(ArgumentsConstants.NORMALIZE, "true").equals("true") || isOnlyTestNormalize)
            {
                FileSystem.get(conf).delete(new Path(outputBase + "/normalize"), true);
            }
        }


        logger.info("Core Part for K-Means is Successfully Finished...");
		return 0;
	}

    private String[] getModelInfoStrArr(Configuration conf, String clusterCenterPath) throws Exception
    {
        String returnArr[] = new String[3];
        for(int i=0; i<returnArr.length; i++) returnArr[i] = "";
        int clusterCnt = 0;
        String baseInfoStr = "";

        FileSystem fs = FileSystem.get(conf);
        FileStatus[] status = fs.listStatus(new Path(clusterCenterPath));
        for (int i=0;i<status.length;i++)
        {
            FSDataInputStream fin = fs.open(status[i].getPath());
            BufferedReader br = new BufferedReader(new InputStreamReader(fin, Constants.UTF8));

            String readStr;
            while((readStr = br.readLine())!=null)
            {
                clusterCnt++;
                if(baseInfoStr.length() < 1) baseInfoStr = readStr;
            }
            br.close();
            fin.close();
        }
        returnArr[2] = clusterCnt + "";

        String tokens[] = baseInfoStr.split(conf.get(ArgumentsConstants.DELIMITER, "\t"));
        for(int i=1; i<tokens.length; i++)
        {
            if(tokens[i+1].contains(KMeansClusterInfoMgr.mNominalDelimiter)) returnArr[1] += "," + tokens[i];
            else returnArr[0] += "," + tokens[i];
            i++;
        }

        if(returnArr[0].length() == 0) returnArr[0] = "-1";
        else returnArr[0] = returnArr[0].substring(1);

        if(returnArr[1].length() == 0) returnArr[1] = "-1";
        else returnArr[1] = returnArr[1].substring(1);

        return returnArr;
    }

    private int execNormalize(Configuration conf, String modelBasePathStr, String outputPath) throws Exception
    {
        Job job = new Job(this.getConf());
        NormalizeDriver normal = new NormalizeDriver();

        job.getConfiguration().set(ArgumentsConstants.DELIMITER, conf.get(ArgumentsConstants.DELIMITER, "\t"));
        String targetIndexStr = normal.setMinMax(job.getConfiguration(), modelBasePathStr + "/normalize_numericStat");

        FileInputFormat.addInputPaths(job, conf.get(ArgumentsConstants.INPUT_PATH));
        FileOutputFormat.setOutputPath(job, new Path(outputPath));

        job.getConfiguration().set(ArgumentsConstants.TARGET_INDEX, targetIndexStr);
        job.getConfiguration().set(ArgumentsConstants.EXCEPTION_INDEX, "-1");
        job.getConfiguration().set(ArgumentsConstants.REMAIN_FIELDS, "true");
        job.getConfiguration().set(ArgumentsConstants.TEMP_DELETE, "true");

        job.setJarByClass(KMeansDriver.class);

        job.setMapperClass(NormalizeMapper.class);

        job.setMapOutputKeyClass(NullWritable.class);
        job.setMapOutputValueClass(Text.class);

        job.setNumReduceTasks(0);

        if(!job.waitForCompletion(true))
        {
            logger.error("Error: MR for Normalization is not Completion");
            logger.info("MR-Job is Failed..");
            return 1;
        }

        return 0;
    }

    @Deprecated
    public int run_old(String[] args) throws Exception {

        logger.info("K-Means Clustering MR-Job is Started..");

        Configuration conf = this.getConf();
        if(!ConfigurationVariable.setFromArguments(args, conf))
        {
            Usage.printUsage(Constants.ALGORITHM_KMEANS_CLUSTERING);
            logger.info("K-Means Clustering MR-Job is Failed..: Configuration Failed");
            return 1;
        }

        if(conf.get(ArgumentsConstants.NORMALIZE, "true").equals("true"))
        {
            logger.info("Normalization for K-Means is Started..");
            String normalizationJobOutput = conf.get(ArgumentsConstants.OUTPUT_PATH, null) + "/normalize";
            String params[] = getParametersForNormalization(conf, normalizationJobOutput);

            int res = ToolRunner.run(new NormalizeDriver(), params);
            if(res!=0)
            {
                logger.info("Normalization for K-Means is Failed..");

                return 1;
            }

            logger.info("Normalization for K-Means is Successfully Finished..");
            conf.set(ArgumentsConstants.INPUT_PATH, normalizationJobOutput);
        }

        /**
         * clustering process
         * 1. set initial cluster center (old-cluster): Main Driver Class
         * 		numeric - distribution based ( min + ((max-min)/clusterCnt*clusterIndex))
         * 		nominal - frequency ratio
         * 2.assign cluster to each data and update cluster center (MR Job)
         * 		2.1 assign cluster to data using cluster-data distance (for all data): Map
         * 		2.2 update cluster center (new-cluster): Reduce
         * 4. compare old-cluster / new-cluster: Main Driver Class
         * 5. decision: Main Driver Class
         * 		if(euqal) exec 2.1 and finish
         * 		else if(maxIter) exec 2.1 and finish
         * 		else
         * 		{
         * 			pre-cluster <= post-cluster
         * 			goto Step 2
         * 		}
         *
         * cluster representation
         * 0. id
         * 1. numeric => index '' value
         * 2. nominal => index '' value@@ratio[@@value@@ratio]+
         *
         * cluster-data distance
         * 1. numeric => clusterVal - dataVal
         * 2. nominal => 1 - ratio(cluster=data)
         * => Total Distance (Euclidean / Manhatan)
         */
        logger.info("Core Part for K-Means is Started..");

        // TODO: convergence rate
        mConvergeRate = Double.parseDouble(conf.get(ArgumentsConstants.CLUSTER_TRAINING_CONVERGE, mConvergeRate + ""));
        setIndexArray(conf);

        int iterCnt = 0;
        String outputBase = conf.get(ArgumentsConstants.OUTPUT_PATH, null);
        String oldClusterPath = outputBase + "/cluster_center_0";
        String newClusterPath;

        logger.info("> Cluster Center Initializing is Started....");
        setInitialClusterCenter(conf, oldClusterPath);							// init cluster
        logger.info("> Cluster Center Initializing is Finished....");
        logger.info("> Iteration(Cluster Assign / Cluster Update) is Started....");
        while(true)
        {
            iterCnt++;
            logger.info("> Iteration: " + iterCnt);

            newClusterPath = outputBase + "/cluster_center_" + iterCnt;
            conf.set(ArgumentsConstants.OUTPUT_PATH, newClusterPath);
            logger.info(">> MR-Job for Cluster Assign / Cluster Update is Started..");
            if(!assignAndResetCluster(conf, oldClusterPath))
            {
                logger.info(">> MR-Job for Cluster Assign / Cluster Update is Failed..");
                return 1;			// MR Job, assign and update cluster
            }
            logger.info(">> MR-Job for Cluster Assign / Cluster Update is Finished...");

            logger.info(">> Iteration Break Condition Check..");
            if(isClustersEqual(conf, oldClusterPath, newClusterPath)) break;	// cluster check
            else if(iterCnt >= Integer.parseInt(conf.get(ArgumentsConstants.MAX_ITERATION, "1"))) break;

            logger.info(">> Iteration is not Broken. Continue Next Iteration..");
            oldClusterPath = newClusterPath;
        }
        logger.info(">> Iteration is Broken..");
        logger.info("> Iteration(Cluster Assign / Cluster Update) is Finished....");

        if(conf.get(ArgumentsConstants.FINAL_RESULT_GENERATION, "true").equals("true"))
        {
            conf.set(ArgumentsConstants.OUTPUT_PATH, outputBase + "/clustering_result");
            if(!finalAssignCluster(conf, newClusterPath)) return 1;					// Map Job, final assign cluster
            logger.info("> Final Cluster Assign and Compute Distance..");


            // TODO: purity gen > nominal stat
            /*
            parameter setting > nominalStatMR
             */

            conf.set(ArgumentsConstants.INPUT_PATH, conf.get(ArgumentsConstants.OUTPUT_PATH));
            conf.set(ArgumentsConstants.OUTPUT_PATH, outputBase + "/purity_mr");
            int clusterIndex = ClusterCommon.getClusterIndex(conf);
            String params[] = ClusterCommon.getParametersForPurity(conf, clusterIndex);

            int res = ToolRunner.run(new NominalStatsDriver(), params);
            if(res!=0)
            {
                logger.info("Purity Computation (Nominal Stats) for K-Means is Failed..");

                return 1;
            }

            // result merge - mr job files
            ClusterCommon.finalPurityGen(conf, outputBase + "/purity");
            FileSystem.get(conf).delete(new Path(conf.get(ArgumentsConstants.OUTPUT_PATH)), true);
            logger.info("Purity Computation (Nominal Stats) for K-Means is Successfully Finished..");
        }


        if(conf.get(ArgumentsConstants.TEMP_DELETE, "true").equals("true"))
        {
            logger.info("Temporary Files are Deleted..: Cluster Center Info Files");
            for(int i=0; i<iterCnt; i++)
            {
                FileSystem.get(conf).delete(new Path(outputBase + "/cluster_center_" + i), true);
            }

            //TODO: normalization modify
            if(conf.get(ArgumentsConstants.NORMALIZE, "true").equals("true"))
            {
                FileSystem.get(conf).delete(new Path(outputBase + "/normalize"), true);
            }
        }

        logger.info("Core Part for K-Means is Successfully Finished...");
        return 0;
    }



    /**
    * @desc initialize cluster center info (file generation)
    *
    * @parameter
    *       conf        configuration identifier for job (non-mr)
    *       clusterOutputPath       file path for cluster info
    * @return
    */
	private void setInitialClusterCenter(Configuration conf, String clusterOutputPath) throws Exception
	{
		/**
		 * TODO:
		 * Current Process
		 * 		- get top n data (n is defined cluster count)
		 * 		- set each data to initial cluster center
		 * 
		 * Following Process is reasonable. => MR Job
		 * 		1. Distribution
		 * 			- get statistics(distribution) for all attributes
		 * 			- use min/max and freq for initial cluster center setting
		 * 		numeric => (max-min) / cluster count
		 * 		nominal => each value (freq sort) 
		 */
		FileSystem fs = FileSystem.get(conf);
				
		String readStr, tokens[];
		int index = 0;
		int clusterCnt = Integer.parseInt(conf.get(ArgumentsConstants.CLUSTER_COUNT, "1"));
		KMeansClusterInfoMgr clusters[] = new KMeansClusterInfoMgr[clusterCnt];

        Path inputPath = new Path(conf.get(ArgumentsConstants.INPUT_PATH));
        inputPath = CommonMethods.findFile(fs, inputPath);

		FSDataInputStream fin = fs.open(inputPath);
		BufferedReader br = new BufferedReader(new InputStreamReader(fin, Constants.UTF8));
		
		while((readStr = br.readLine())!=null)
		{
			clusters[index] = new KMeansClusterInfoMgr();
			clusters[index].setClusterID(index);
			
			tokens = readStr.split(conf.get(ArgumentsConstants.DELIMITER, "\t"));
			for(int i=0; i<tokens.length; i++)
			{
				if(CommonMethods.isContainIndex(mIndexArr, i, true)
						&& !CommonMethods.isContainIndex(mExceptionIndexArr, i, false))
				{
					if(CommonMethods.isContainIndex(mNominalIndexArr, i, false))
					{
						clusters[index].addAttributeValue(i, tokens[i], ConfigurationVariable.NOMINAL_ATTRIBUTE);
					}
					else clusters[index].addAttributeValue(i, tokens[i], ConfigurationVariable.NUMERIC_ATTRIBUTE);
				}
			}
			
			index++;
			if(index >= clusterCnt) break;
            else
            {
                int skipCnt = (int) (Math.random() * 5);
                for(int i=0; i<skipCnt; i++) br.readLine();
            }
		}
		
		br.close();
		fin.close();


        if(index < clusterCnt)
        {
            logger.error("Initial Cluster Setting Error.");
            logger.error("> the number of initial clusters is less than the number of required clusters.");
            throw new Exception();
        }

		
		FSDataOutputStream fout = fs.create(new Path(clusterOutputPath + "/part-r-00000"), true);		
		BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fout, Constants.UTF8));

		for(int i=0; i<clusters.length; i++)
		{
			bw.write(clusters[i].getClusterInfoString(conf.get(ArgumentsConstants.DELIMITER, "\t")) + "\n");
		}
		
		bw.close();
		fout.close();
		
	}




    /*
    * @desc kmenas update mr job - cluster assign and cluster info update
    *
    * @parameter
    *       conf        configuration identifier for job
    *       oldClusterPath       file path for ole cluster info (before kmeans update)
    * @return
    *       boolean - if mr job is successfully finished
    */
	private boolean assignAndResetCluster(Configuration conf, String oldClusterPath) throws Exception
	{
		/**
		 * Map Job
		 * 		- load old cluster center
		 * 		- each data -> compute distance to each cluster
		 * 		- assign cluster number using distance: Map Key for Reduce
		 * 
		 * Reduce Job
		 * 		- compute new center
		 * 		- save key and computed center info
		 */
		
		Job job = new Job(this.getConf());
		
		FileInputFormat.addInputPaths(job, conf.get(ArgumentsConstants.INPUT_PATH));
		FileOutputFormat.setOutputPath(job, new Path(conf.get(ArgumentsConstants.OUTPUT_PATH)));
		
		job.getConfiguration().set(ArgumentsConstants.DELIMITER, conf.get(ArgumentsConstants.DELIMITER, "\t"));
		job.getConfiguration().set(ArgumentsConstants.TARGET_INDEX, conf.get(ArgumentsConstants.TARGET_INDEX, "-1"));
		job.getConfiguration().set(ArgumentsConstants.NOMINAL_INDEX, conf.get(ArgumentsConstants.NOMINAL_INDEX, "-1"));
		job.getConfiguration().set(ArgumentsConstants.EXCEPTION_INDEX, conf.get(ArgumentsConstants.EXCEPTION_INDEX, "-1"));
		job.getConfiguration().set(ArgumentsConstants.CLUSTER_COUNT, conf.get(ArgumentsConstants.CLUSTER_COUNT, "1"));		
		job.getConfiguration().set(ArgumentsConstants.CLUSTER_PATH, oldClusterPath);
//		job.getConfiguration().set("subDelimiter", mNominalDelimiter);
		
		job.setJarByClass(KMeansDriver.class);
		
		job.setMapperClass(KMeansClusterAssignMapper.class);
		job.setReducerClass(KMeansClusterUpdateReducer.class);

		job.setMapOutputKeyClass(IntWritable.class);
		job.setMapOutputValueClass(Text.class);

		job.setOutputKeyClass(NullWritable.class);
		job.setOutputValueClass(Text.class);

		if(!job.waitForCompletion(true))
    	{
        	logger.error("Error: MR for KMeans(Rutine) is not Completeion");
        	return false;
        }
		
        return true;
	}

   /**
    * @desc compare old and new cluster are equal
    *
    * @parameter
    *       conf        configuration identifier for job (non-mr)
    *       oldClusterPath       file path for ole cluster info (before kmeans update)
    * @return
    *       boolean - if job is successfully finished
    */
	private boolean isClustersEqual(Configuration conf, String oldClusterPath, String newClusterPath) throws Exception
	{
		/**
		 * Check clusters are equal (cluster index and center info)
		 * Load 2 files
		 * 		HashMap Structure: Key - Cluster Index
		 * 							Value - Cluster Center Info. String
		 * for each Value of Keys, check
		 */
		
		int clusterCnt = Integer.parseInt(conf.get(ArgumentsConstants.CLUSTER_COUNT, "1"));
		String delimiter = conf.get(ArgumentsConstants.DELIMITER, "\t");
				
		KMeansClusterInfoMgr oldClusters[] = KMeansClusterInfoMgr.loadClusterInfoFile(conf, new Path(oldClusterPath), clusterCnt, delimiter);
		KMeansClusterInfoMgr newClusters[] = KMeansClusterInfoMgr.loadClusterInfoFile(conf, new Path(newClusterPath), clusterCnt, delimiter);
		
		for(int i=0; i<clusterCnt; i++)
		{
            // TODO: convergence rate check
			if(!oldClusters[i].isEqualClusterInfo(newClusters[i], mConvergeRate)) return false;
		}
		
		return true;
	}

   /**
    * @desc final cluster assign to each data
    *
    * @parameter
    *       conf        configuration identifier for job
    *       clusterPath       file path for final cluster info
    * @return
    *       boolean - if mr job is successfully finished
    */
	private boolean finalAssignCluster(Configuration conf, String clusterPath) throws Exception
	{
		/**
		 * Map Job (ref. Map job of 'assignAndResetCluster()')
		 * 
		 * If cat use MR default delimiter then, use Map job of 'assignAndResetCluster()'
		 * else, * Modified Map Job for Writing (no key, key is used to last attribute)
		 */
		Job job = new Job(this.getConf());

        FileInputFormat.addInputPaths(job, conf.get(ArgumentsConstants.INPUT_PATH));
        FileOutputFormat.setOutputPath(job, new Path(conf.get(ArgumentsConstants.OUTPUT_PATH)));

        job.getConfiguration().set(ArgumentsConstants.DELIMITER, conf.get(ArgumentsConstants.DELIMITER, "\t"));
        job.getConfiguration().set(ArgumentsConstants.TARGET_INDEX, conf.get(ArgumentsConstants.TARGET_INDEX, "-1"));
        job.getConfiguration().set(ArgumentsConstants.NOMINAL_INDEX, conf.get(ArgumentsConstants.NOMINAL_INDEX, "-1"));
        job.getConfiguration().set(ArgumentsConstants.EXCEPTION_INDEX, conf.get(ArgumentsConstants.EXCEPTION_INDEX, "-1"));
        job.getConfiguration().set(ArgumentsConstants.CLUSTER_COUNT, conf.get(ArgumentsConstants.CLUSTER_COUNT, "1"));
        job.getConfiguration().set(ArgumentsConstants.CLUSTER_PATH, clusterPath);
//        job.getConfiguration().set("nominalDelimiter", mNominalDelimiter);

		job.setJarByClass(KMeansDriver.class);
		
		job.setMapperClass(KMeansClusterAssignFinalMapper.class);

		job.setMapOutputKeyClass(NullWritable.class);
		job.setMapOutputValueClass(Text.class);

		job.setNumReduceTasks(0);
		
		if(!job.waitForCompletion(true))
    	{
        	logger.error("Error: MR for KMeans(Final) is not Completion");
        	return false;
        }
		
        return true;
	}


   /**
    * @desc arguments setting for normalization mr job
    *
    * @parameter
    *       conf        configuration identifier for job
    *       outputPath       output path for normalization job
    * @return
    *       string array - arguments for normalization job
    */
	private String[] getParametersForNormalization(Configuration conf, String outputPath) throws Exception 
	{
		String params[] = new String[16];
		
		params[0] = ArgumentsConstants.INPUT_PATH;
		params[1] = conf.get(ArgumentsConstants.INPUT_PATH, null);
		
		params[2] = ArgumentsConstants.OUTPUT_PATH;
		params[3] = outputPath;
		
		params[4] = ArgumentsConstants.DELIMITER;
		params[5] = conf.get(ArgumentsConstants.DELIMITER, "\t");
		
		params[6] = ArgumentsConstants.TARGET_INDEX;
		params[7] = conf.get(ArgumentsConstants.TARGET_INDEX, "-1");
		
		String nominalIndexList = conf.get(ArgumentsConstants.NOMINAL_INDEX, "-1");
		String exceptionIndexList = conf.get(ArgumentsConstants.EXCEPTION_INDEX, "-1");
		if(!nominalIndexList.equals("-1"))
		{
			if(exceptionIndexList.equals("-1")) exceptionIndexList = nominalIndexList;
			else exceptionIndexList += "," + nominalIndexList;
		}
		params[8] = ArgumentsConstants.EXCEPTION_INDEX;
		params[9] = exceptionIndexList;
		
		params[10] = ArgumentsConstants.REMAIN_FIELDS;
		params[11] = "true";
		
		// TODO: normalization modify
        params[12] = ArgumentsConstants.TEMP_DELETE;
        params[13] = "false";
		//params[13] = conf.get(ArgumentsConstants.TEMP_DELETE, "true");
		
		params[14] = ArgumentsConstants.MR_JOB_STEP;
		params[15] = conf.get(ArgumentsConstants.MR_JOB_STEP, "1");
		
		return params;
	}

   /**
    * @desc convert from comma based string index list to int array
    *
    * @parameter
    *       conf        configuration identifier for job
    * @return
    */
	private void setIndexArray(Configuration conf)
	{
		mIndexArr = CommonMethods.convertIndexStr2IntArr(conf.get(ArgumentsConstants.TARGET_INDEX,  "-1"));
		mNominalIndexArr = CommonMethods.convertIndexStr2IntArr(conf.get(ArgumentsConstants.NOMINAL_INDEX,  "-1"));
		mExceptionIndexArr = CommonMethods.convertIndexStr2IntArr(conf.get(ArgumentsConstants.EXCEPTION_INDEX,  "-1"));
	}

	public static void main(String args[]) throws Exception 
	{
		int res = ToolRunner.run(new KMeansDriver(), args);
        System.exit(res);
	}
}
