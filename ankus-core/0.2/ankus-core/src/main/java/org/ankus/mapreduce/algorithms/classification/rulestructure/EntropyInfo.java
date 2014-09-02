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
package org.ankus.mapreduce.algorithms.classification.rulestructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

/**
 * EntropyInfo
 * @desc
 *
 * @version 0.1
 * @date : 2013.11.13
 * @author Moonie Song
 */
public class EntropyInfo {

    public double entropy = 1.0;

    double m_log2 = Math.log(2);

    public ArrayList<String> attrValueList = new ArrayList<String>();
    public ArrayList<Integer> attrValueTotalCntList = new ArrayList<Integer>();
    public ArrayList<Double> attrValuePurityList = new ArrayList<Double>();
    public ArrayList<String> attrMaxClassList = new ArrayList<String>();

    public void setValueList(ArrayList<String> valueList)
    {
        attrValueList.addAll(valueList);
    }

    public void addAttributeDist(int sumArr[], int maxArr[], String classArr[])
    {
        int len = sumArr.length;

        for(int i=0; i<len; i++)
        {
            attrValueTotalCntList.add(sumArr[i]);
            attrValuePurityList.add((double)maxArr[i]/(double)sumArr[i]);
            attrMaxClassList.add(classArr[i]);
        }
    }

    public String toString(String delimiter)
    {

        String str = "" + entropy;

        int len = attrValueList.size();
        for(int i=0; i<len; i++)
        {
            str += delimiter + attrValueList.get(i)
                    + delimiter + attrValueTotalCntList.get(i)
                    + delimiter + attrValuePurityList.get(i)
                    + delimiter + attrMaxClassList.get(i);
        }

        return str;
    }

    public void computeIGVal(HashMap<String, HashMap<String, Integer>> attrClassList)
    {
        int attrSize = attrClassList.size();

        int sumArr[] = new int[attrSize];
        double igArr[] = new double[attrSize];
        int maxArr[] = new int[attrSize];
        String classArr[] = new String[attrSize];
        int totSum = 0;

        for(int i=0; i<attrSize; i++)
        {
            String valueStr = attrValueList.get(i);
            HashMap<String, Integer> classDistList = attrClassList.get(valueStr);

            int classCnt = classDistList.size();
            Iterator<String> classIter = classDistList.keySet().iterator();
            sumArr[i] = 0;
            int classArrInt[] = new int[classCnt];
            int iterIndex = 0;
            while(classIter.hasNext())
            {
                String classVal = classIter.next().toString();
                classArrInt[iterIndex] = classDistList.get(classVal);
                sumArr[i] += classArrInt[iterIndex];
                if(maxArr[i] < classArrInt[iterIndex])
                {
                    maxArr[i] = classArrInt[iterIndex];
                    classArr[i] = classVal;
                }
                iterIndex++;
            }

            igArr[i] = getInformationValue(classArrInt, sumArr[i]);
            totSum += sumArr[i];
        }

        addAttributeDist(sumArr, maxArr, classArr);
        entropy = getEntropy(sumArr, totSum, igArr);
    }

    private double getInformationValue(int[] classDist, int sum)
    {
        double val = 0.0;
        for(int c: classDist)
        {
            double p = (double)c/(double)sum;
            if(c > 0) val = val + (p * Math.log(p)/m_log2);
        }

        if(val==0) return 0;
        else return val * -1;
    }

    private double getEntropy(int[] attrSumArr, int totalSum, double[] IGArr)
    {
        double val = 0.0;
        for(int i=0; i<attrSumArr.length; i++)
        {
            val = val + ((double)attrSumArr[i] / (double)totalSum * IGArr[i]);
        }

        return val;
    }
}
