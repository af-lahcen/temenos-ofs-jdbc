package org.t24.driver;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.*;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.ArrayList;

/**
 *
 * @author avityuk
 */
public class T24QueryFormatter {
	public enum QueryType { APP, ENQ }

    //private static SimpleDateFormat sdfDateParse = new SimpleDateFormat("yyyy-MM-dd");
    //private static SimpleDateFormat sdfDate = new SimpleDateFormat("yyyyMMdd");
    private T24Connection con;    //connection
    //private String query;         //original query
    //private List<String> param;   //input parameters
    
    private final static int STATE_DEF = 0;
    private final static int STATE_OFS = 1;
    private final static String OFSCODEWORLD = "SENDOFS";
    
    private List<String> sentOfsQueries=new ArrayList<String>();

    //private static int STATE_POST=3;

    public T24QueryFormatter(Connection con) {
        this.con = (T24Connection)con;
    }
    public T24QueryFormatter() {
    }
    
    public List<String> getSentOfsQueries(){
    	return sentOfsQueries;
    }
    
	public T24ResultSet execute(String query, List<String> queryParam) throws SQLException{
		//cut off the optional SELECT keyword
		sentOfsQueries.clear();
		if( query.matches("^SELECT\\s"))query=query.substring(7).trim();
		
		String ofsHeader=null;
		T24ResultSet result=null;
		Map<String,String> ofsParam=new LinkedHashMap<String,String>();
		
		int state=STATE_DEF; 
		StringTokenizer st = new StringTokenizer(query, "\r\n");
		while(st.hasMoreElements()){


			String line = st.nextToken().trim();

			//skip empty and commented lines
			if (line.length() < 1 || line.startsWith("//")) {
				continue;
			}

			if(line.matches("^SENDOFS\\s.*$")) {
				switch(state){
					case STATE_DEF: 
						break;
					case STATE_OFS: 
						throw new T24Exception("Parser error: met SENDOFS when previous not END-ed");
					default:
						throw new T24Exception("Wrong parser status: "+state); //should not happend
				}
				state = STATE_OFS;
				ofsHeader = prepareHeader(line, queryParam);

			}else if(line.equals("END")){
				switch(state){
					case STATE_DEF: 
						throw new T24Exception("Parser error: met END without SENDOFS");
					case STATE_OFS:
						result = executeOfs(ofsHeader, ofsParam, result);
						break;
					default:
						throw new T24Exception("Wrong parser status: "+state); //should not happend
				}
				state=STATE_DEF;
			}else{
				//usual evaluate lines here
				switch(state){
					case STATE_DEF:
						postEvaluate(line,result,queryParam);
						break;
					case STATE_OFS:
						evaluate(line, null, queryParam, ofsParam);
						break;
					default:
						throw new T24Exception("Wrong parser status: "+state);
				}
			}
		}
		
		if(state==STATE_OFS)throw new T24Exception("Parser error: last SENDOFS not ended with END");
		//return result from last ofs
		return result;
	}
	
	/**
	* evaluate the expression and apply evaluated parameters to resultset and to query parameters
	* " ?[0-9] = expression " must go to queryParam
	* " ?xxx = expression " must throw not supported exception (maybe in the future we will use it)
	* " xxx = expression " should go to the result (new column evaluated for each row)
	*/
	protected void postEvaluate(String line, T24ResultSet result, List<String> queryParam) throws SQLException{
		if (result == null) {return;}
		Map<String,String> postParam=new HashMap<String,String>(2); //initial count = 2
		
		
		for(int i=1; i <= result.getRowCount(); i++ ) {
			evaluate(line, ((T24ResultSetMetaData)result.getMetaData()).getColumnNames(), result.getDataRow(i), postParam);
			//now go through all key/values
			for( Map.Entry<String,String> entry : postParam.entrySet() ) {
				String key=entry.getKey();
				if(key.startsWith("?")) {
					//key started with ? so set the value for query parameter
					try {
						int index=Integer.parseInt(key.substring(1))-1;
						queryParam.set(index,entry.getValue());
					} catch ( Exception e ) {
						throw new T24Exception("Wrong post process index in expression: "+line,e);
					}
				} else {
					//usual key, so add column for the resultset
					result.setValue(i,key,entry.getValue());
				}
			}
			
		}
	}
	
	protected String prepareHeader(String ofsHeader,List<String> queryParam)throws SQLException{
		//evaluate and replace all {{expression}}
		//no special formatting here put result as is
		int p1 = 0, p2 = 0;
		Map<String,String> eval = new HashMap<String,String>(2); //initial count = 2
		StringBuilder out = new StringBuilder();
		
		p1 = ofsHeader.indexOf("{{");
		while ( p1>=0 ) {
			out.append( ofsHeader.substring(p2, p1) );
			p2 = ofsHeader.indexOf("}}",p1);
			
			if(p2<0)throw new T24Exception("Can't find close tag for header expression: "+ofsHeader);
			String expression=ofsHeader.substring(p1+2, p2);
			
			evaluate("x="+expression, null, queryParam, eval);
			out.append( eval.get("x") );
			
			p1=ofsHeader.indexOf("{{",p2);
			p2+=2;
		}
		if(p2<ofsHeader.length())out.append(ofsHeader.substring(p2));
		return out.toString();
	}

    
	
	protected T24ResultSet executeOfs(String ofsHeader, Map<String,String> ofsParam, T24ResultSet oldResult){
		T24ResultSet rs = null;
		//do final prepare of the ofs
		///ofsHeader:
		///remove SENDOFS
		ofsHeader = ofsHeader.substring(OFSCODEWORLD.length()).trim();
		boolean isOfsSend = Boolean.parseBoolean(ofsHeader.replaceAll("^(TRUE|FALSE)\\s+(.*)$", "$1")); 

		if (!isOfsSend){
			System.out.println("Skip OFS = " + ofsHeader + ofsParam);
			rs = oldResult;
		}else{
			ofsHeader = ofsHeader.replaceAll("^(.*)[\\s+](.*)$", "$2");
			
			QueryType queryType;

			if (ofsHeader.matches("^ENQUIRY.SELECT.*$")){
				queryType = QueryType.ENQ;
			}else {
				queryType = QueryType.APP;
			}

			String ofs = ofsHeader;
			String ofsBody="";
			for (String columnName : ofsParam.keySet()) {
				String columnValue = ofsParam.get(columnName);
				ofsBody = prepareField(columnName, columnValue, queryType);
				ofs += ofsBody;
			}

			try{
				System.out.println("Send OFS = " + ofs);
				sentOfsQueries.add(ofs);

				String ofsResp = con.t24Send(ofs);
				//create resultset from responce
				rs = new T24ResultSet(ofs, ofsResp);
			}catch (Exception e){
				e.printStackTrace();
			}
		}

		ofsParam.clear();
		return rs;
	}
    
    /** 
     * evaluate the one-line command
     * @param line one-line command ( CR & LF not expected )
     * @param colName list of column names (could be null)
     * @param colValue list of column values
     * @param result a Map where to store result : column - bdec pair evaluated from line. Better to use LinkedHashMap, so order will be preserved.
     */
    private void evaluate(String line, List<String> colName, List<String> colValue, Map<String, String> result) throws SQLException {
        line = line.trim();
        if (line.length() == 0) {
            return;
        }
        if (line.startsWith("//")) {
            return;
        }
        int position;
        position = line.indexOf('=');
        if (position < 0) {
            throw new T24Exception("Syntax error: '=' expected in line: " + line);
        }
        String fieldName = line.substring(0, position).trim();
        String expression = line.substring(position + 1).trim();

        String command = expression.replaceAll("^(\\w+)\\s.*$", "$1");
        expression = expression.replaceAll("^(\\w+)\\s(.*)$", "$2").trim();
        List<String> commandParams = getCommandParams(expression);

        if ("const".equals(command)) {
            evaluateConst(fieldName, commandParams, colName, colValue, result);
        } else if ("decode".equals(command)) {
            evaluateDecode(fieldName, commandParams, colName, colValue, result);
        } else if ("toCent".equals(command)) {
            evaluateToCent(fieldName, commandParams, colName, colValue, result);
        } else if ("set".equals(command)) {
            evaluateSet(fieldName, commandParams, colName, colValue, result);
        } else if ("fromCent".equals(command)) {
            evaluateFromCent(fieldName, commandParams, colName, colValue, result);
        } else if ("split".equals(command)) {
            evaluateSplit(fieldName, commandParams, colName, colValue, result);
        } else if ("substr".equals(command)) {
            evaluateSubstr(fieldName, commandParams, colName, colValue, result);
        } else if ("setIfNull".equals(command)) {
            evaluateSetIfNull(fieldName, commandParams, colName, colValue, result);
        } else {
            throw new T24Exception("Unknown command : " + command);
        }
    }

    private List<String> getCommandParams(String expression) {
        List<String> commnadParams = new ArrayList();
        StringTokenizer st = new StringTokenizer(expression);
        while (st.hasMoreElements()) {
            commnadParams.add(st.nextToken());
        }
        return commnadParams;
    }

    private void evaluateToCent(String fieldName, List<String> commandParams, List<String> colName, List<String> colValue, Map<String, String> result) throws T24Exception {

        if (commandParams == null || colValue == null) {
            throw new T24Exception("Incorrect parameters or ResultSet: ");
        }
        if (!commandParams.get(0).startsWith("?")) {
            throw new T24Exception("Incorrect parameter: " + commandParams.get(0));
        } else {
            int valueIndex = colName.indexOf(commandParams.get(0).substring(1));
            if (valueIndex != -1) {
                if ("".equals(colValue.get(valueIndex).trim())) {
                    result.put(fieldName, "");
                } else {
                    try {
                        BigDecimal value = new BigDecimal(colValue.get(valueIndex).trim());
                        value = value.multiply(new BigDecimal("100")).setScale(0);
                        result.put(fieldName, value.toString());
                    } catch (Exception e) {
                        result.put(fieldName, "");
                    }
                }
            } else {
                throw new T24Exception("Incorrect parameter: " + commandParams.get(0));
            }
        }
    }

    private String getValueForComandParam(int paramNumber, List<String> commandParams, List<String> colName, List<String> colValue) throws T24Exception{
        if (commandParams == null || colValue == null) {
            throw new T24Exception("Incorrect parameters or ResultSet: ");
        }
		String key=commandParams.get(paramNumber);
		int valueIndex;
		
        if (!key.startsWith("?")) 
        	throw new T24Exception("Incorrect parameter: " + key);
        
		try {
			valueIndex=Integer.valueOf(key.substring(1))-1;
		} catch(Exception e) {
			if(colName==null)throw new T24Exception("Can't get value for named parameter "+key);
			valueIndex = colName.indexOf(key.substring(1).toLowerCase());
		}
		
		if (valueIndex == -1) 
			throw new T24Exception("Can't find parameter : " + key);
		
		String value=colValue.get(valueIndex);
		return (value==null?"":value.trim());
	}

    private void evaluateDecode(String fieldName, List<String> commandParams, List<String> colName, List<String> colValue, Map<String, String> result) throws T24Exception {
        String value = "";
        boolean changed = false;
		value = getValueForComandParam(0, commandParams, colName, colValue);
        for (int i = 1; i < commandParams.size() - 1; i += 2) {
            if (value.equals(commandParams.get(i).trim())) {
                value = commandParams.get(i + 1);
                changed = true;
            }
        }
        if (!changed && commandParams.size() % 2 == 0) {
            value = commandParams.get(commandParams.size() - 1);
        }
        result.put(fieldName, value);
    }

    private void evaluateConst(String fieldName, List<String> commandParams, List<String> colName, List<String> colValue, Map<String, String> result) {
        result.put(fieldName, commandParams.get(0));
    }

    private void evaluateSet(String fieldName, List<String> commandParams, List<String> colName, List<String> colValue, Map<String, String> result) throws T24Exception {
        String value = getValueForComandParam(0, commandParams, colName, colValue);
        result.put(fieldName, value);
    }

    private void evaluateSetIfNull(String fieldName, List<String> commandParams, List<String> colName, List<String> colValue, Map<String, String> result) throws T24Exception {
		String value = getValueForComandParam(0, commandParams, colName, colValue);

        if (value == null || "".equals(value)) {
            result.put(fieldName, commandParams.get(1));
        } else {
            result.put(fieldName, value);
        }
    }

    private void evaluateFromCent(String fieldName, List<String> commandParams, List<String> colName, List<String> colValue, Map<String, String> result) throws T24Exception {
		String value = getValueForComandParam(0, commandParams, colName, colValue);
		if(value==null||value.length()==0) {
			result.put(fieldName, null);
		} else {
			BigDecimal bdec = new BigDecimal(value);
			bdec = bdec.multiply(new BigDecimal("0.01"));
			value = bdec.toString();
			result.put(fieldName, value);
		}
    }

    private void evaluateSplit(String fieldName, List<String> commandParams, List<String> colName, List<String> colValue, Map<String, String> result) throws T24Exception {
        String str = getValueForComandParam(0, commandParams, colName, colValue);
        int counter = 1;
        int length = Integer.parseInt(commandParams.get(1));
        while (str.length() > 0) {
            String value = substr(str, 0, length);
            result.put(fieldName.replaceAll("\\*", Integer.toString(counter)), value);
            str = substr(str, length, str.length());
            counter++;
        }
    }

    private void evaluateSubstr(String fieldName, List<String> commandParams, List<String> colName, List<String> colValue, Map<String, String> result) throws T24Exception {
		String str = getValueForComandParam(0, commandParams, colName, colValue);
        int indexStart = Integer.parseInt(commandParams.get(1));
        int length = Integer.parseInt(commandParams.get(2));

        String value = substr(str, indexStart, length);
        result.put(fieldName, value);
    }

    private String substr(String str, int start, int length) {
        String res;
        int indexBegin;
        int indexEnd;
        if (str == null || str.length() == 0) {
            res = "";
        } else {
            indexBegin = Math.min(start, str.length());
            if (length < 0) {
                indexEnd = str.length();
            } else {
                indexEnd = Math.min(start + length, str.length());
            }
            res = str.substring(indexBegin, indexEnd);
        }
        return res;
    }

    private String prepareField(String fieldName, String value, QueryType queryType) {
        String res;
        if (value == null || value.length() == 0) {
            res = "";
        } else {
            value = value.replace('\"', '\'');
            if (queryType==QueryType.APP) {
                value = value.replaceAll("_", "'_'");
                value = "\"" + value + "\"";
            }
            res = "," + fieldName + "=" + value;
        }
        return res;
    }

}
