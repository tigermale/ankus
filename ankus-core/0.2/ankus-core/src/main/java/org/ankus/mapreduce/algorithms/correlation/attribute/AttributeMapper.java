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
package org.ankus.mapreduce.algorithms.correlation.attribute;

import org.ankus.util.Constants;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import java.io.IOException;

/**
 * AttributeMapper
 * @desc
 *      Here's an version of the similarity coefficient and distance calculation.
 *      1. Dice coefficient 2. Jaccard coefficient 3. Hamming distance
 *
 * Example dataset
 * ------------------------
 * [ Ice Cream Sales vs Temperature ]
    Temperature °C	Ice Cream Sales
    14.2°	        $215
    16.4°	        $325
    11.9°	        $185
 *
 * @return Correlaltion
 *
 * @version 0.1.5
 * @date : 2014.06.05
 * @author Suhyun Jeon
*/
public class AttributeMapper extends Mapper<LongWritable, Text, NullWritable, Text> {

    private String keyIndex;
    private String delimiter;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        Configuration configuration = context.getConfiguration();
        this.keyIndex = configuration.get(Constants.KEY_INDEX);
        this.delimiter = configuration.get(Constants.DELIMITER);
    }

    @Override
    protected void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
       String row = value.toString();
       String[] columns = row.split(delimiter);
       StringBuffer uniqueKeys = new StringBuffer();

//       for(int i=0; i<columns.length; i++){
//           String column = columns[i];
//           if(i == Integer.parseInt(keyIndex)){
//               uniqueKeys.append(column);
//           }else{
//               continue;
//           }
//       }

//       for(int i=1; i<columns.length; i++){
//            value.set(columns[i]);
//            IntegerTwoWritableComparable integerTwoWritableComparable = new IntegerTwoWritableComparable(Integer.parseInt(uniqueKeys.toString()), Integer.parseInt(value.toString()));
//            context.write(new Text(uniqueKeys.toString()), value);
            context.write(NullWritable.get(), value);
//        }
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
    }
}