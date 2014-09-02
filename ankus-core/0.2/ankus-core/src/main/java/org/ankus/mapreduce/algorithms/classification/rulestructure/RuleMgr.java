package org.ankus.mapreduce.algorithms.classification.rulestructure;

import org.ankus.util.ArgumentsConstants;
import org.ankus.util.Constants;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * Created with IntelliJ IDEA.
 * User: moonie
 * Date: 14. 6. 5
 * Time: 오후 6:34
 * To change this template use File | Settings | File Templates.
 */
public class RuleMgr {

    public String loadNonLeafNode(Configuration conf) throws Exception
    {
        String nodeStr = null;
        if(conf.get(ArgumentsConstants.RULE_PATH)==null) return "root";

        Path ruleFilePath = new Path(conf.get(ArgumentsConstants.RULE_PATH));
        FileSystem fs = FileSystem.get(conf);
        FSDataInputStream fin = fs.open(ruleFilePath);
        BufferedReader br = new BufferedReader(new InputStreamReader(fin, Constants.UTF8));

        String readStr;
        br.readLine();
        while((readStr = br.readLine())!=null)
        {
            if((readStr.length() > 0) && readStr.substring(readStr.length() - 5).equals("false"))
            {
                nodeStr = readStr;
                break;
            }
        }

        br.close();
        fin.close();

        return nodeStr;
    }


    public void updateRule(Configuration conf, String oldRulePath, String ruleStr) throws Exception
    {
        // rule selecting
        RuleNodeBaseInfo[] nodes = getSelectedNodes(conf);

        // rule write
        writeRules(conf, nodes, conf.get(ArgumentsConstants.DELIMITER), oldRulePath, ruleStr);

    }

    private void writeRules(Configuration conf, RuleNodeBaseInfo[] nodes, String delimiter, String oldRulePath, String ruleStr) throws Exception
    {
        FileSystem fs = FileSystem.get(conf);

        if(ruleStr.equals("root"))
        {
            FSDataOutputStream fout = fs.create(new Path(conf.get(ArgumentsConstants.RULE_PATH)), true);
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fout, Constants.UTF8));

            bw.write("# [AttributeName-@@Attribute-Value][@@].., Data-Count, Node-Purity, Class-Label, Is-Leaf-Node" + "\n");

            for(int i=0; i<nodes.length; i++)
            {
                bw.write(nodes[i].toString(delimiter) + "\n");
            }

            bw.close();
            fout.close();
        }
        else
        {
            FSDataInputStream fin = FileSystem.get(conf).open(new Path(oldRulePath));
            BufferedReader br = new BufferedReader(new InputStreamReader(fin, Constants.UTF8));

            FSDataOutputStream fout = fs.create(new Path(conf.get(ArgumentsConstants.RULE_PATH)), true);
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fout, Constants.UTF8));

            String readStr;
            while((readStr=br.readLine())!=null)
            {
                if(readStr.equals(ruleStr))
                {
                    if(nodes.length > 1)
                    {
                        bw.write(readStr + "-cont\n");
                        for(int i=0; i<nodes.length; i++)
                        {
                            bw.write(nodes[i].toString(delimiter) + "\n");
                        }
                    }
                    else bw.write(readStr + "-true\n");
                }
                else bw.write(readStr + "\n");
            }

            br.close();
            fin.close();

            bw.close();
            fout.close();
        }
    }


    private RuleNodeBaseInfo[] getSelectedNodes(Configuration conf) throws Exception
    {
        String delimiter = conf.get(ArgumentsConstants.DELIMITER);
        Path entropyPath = new Path(conf.get(ArgumentsConstants.OUTPUT_PATH));
        FileStatus[] status = FileSystem.get(conf).listStatus(entropyPath);

        String selectedStr = "";
        double minEntropy = 9999.0;
        for(int i=0; i<status.length; i++)
        {
            if(!status[i].getPath().toString().contains("part-r-")) continue;

            FSDataInputStream fin = FileSystem.get(conf).open(status[i].getPath());
            BufferedReader br = new BufferedReader(new InputStreamReader(fin, Constants.UTF8));
            String readStr;
            while((readStr = br.readLine())!=null)
            {
                double curEntropy = Double.parseDouble(readStr.split(delimiter)[1]);
                if(minEntropy > curEntropy)
                {
                    minEntropy = curEntropy;
                    selectedStr = readStr;
                }
            }
            br.close();
            fin.close();
        }

        String tokens[] = selectedStr.split(delimiter);
        int attrCnt = (tokens.length - 2) / 4;    // index, entropy and 4-set
        RuleNodeBaseInfo[] retNodes = new RuleNodeBaseInfo[attrCnt];
        int minDataCnt = Integer.parseInt(conf.get(ArgumentsConstants.MIN_LEAF_DATA));
        double minPurity = Double.parseDouble(conf.get(ArgumentsConstants.PURITY));

        for(int i=0; i<attrCnt; i++)
        {
            int base = i * 4;
            int dataCnt = Integer.parseInt(tokens[base+3]);
            double purity = Double.parseDouble(tokens[base+4]);

            retNodes[i] = new RuleNodeBaseInfo(tokens[0]
                    , tokens[base+2]
                    , dataCnt, purity
                    , tokens[base+5]);

            if((minDataCnt >= dataCnt) ||(minPurity < purity)) retNodes[i].setIsLeaf(true);
            else retNodes[i].setIsLeaf(false);
        }

        return retNodes;
    }
}
