package xyz.calvinwilliams.sqlaction;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public class SqlActionGencode {

	public static void main(String[] args) {
		Path					currentPath ;
		Path					sqlactionConfJsonFilePath ;
		String					sqlactionConfJsonFileContent ;
		SqlActionConf			sqlactionConf ;
		Path					dbserverConfJsonFilePath ;
		String					dbserverConfJsonFileContent ;
		DbServerConf			dbserverConf ;
		
		Connection				conn = null ;
		SqlActionDatabase		database = null ;
		
		int						nret = 0 ;
		
		try {
			// Load sqlaction.conf.json
			currentPath = Paths.get(System.getProperty("user.dir")) ;
			
			while( true ) {
				try {
					sqlactionConfJsonFilePath = Paths.get(currentPath.toString(),"sqlaction.conf.json") ;
					sqlactionConfJsonFileContent = new String(Files.readAllBytes(sqlactionConfJsonFilePath)) ;
					break;
				} catch (IOException e) {
					currentPath = currentPath.getParent() ;
					if( currentPath == null ) {
						System.out.println( "*** ERROR : sqlaction.conf.json not found" );
						return;
					}
				}
			}
			
			sqlactionConf = OKJSON.stringToObject( sqlactionConfJsonFileContent, SqlActionConf.class, OKJSON.OKJSON_OTIONS_DIRECT_ACCESS_PROPERTY_ENABLE ) ;
			if( sqlactionConf == null ) {
				System.out.println(sqlactionConfJsonFilePath+" content invalid , errcode["+OKJSON.getErrorCode()+"] errdesc["+OKJSON.getErrorCode()+"]");
				return;
			}
			
			// Load dbserver.conf.json
			while( true ) {
				try {
					dbserverConfJsonFilePath = Paths.get(currentPath.toString(),"dbserver.conf.json") ;
					dbserverConfJsonFileContent = new String(Files.readAllBytes(dbserverConfJsonFilePath)) ;
					break;
				} catch (IOException e) {
					currentPath = currentPath.getParent() ;
					if( currentPath == null ) {
						System.out.println( "*** ERROR : sqlaction.conf.json not found" );
						return;
					}
				}
			}
			
			dbserverConf = OKJSON.stringToObject( dbserverConfJsonFileContent, DbServerConf.class, OKJSON.OKJSON_OTIONS_DIRECT_ACCESS_PROPERTY_ENABLE ) ;
			if( dbserverConf == null ) {
				System.out.println(dbserverConfJsonFilePath+" content invalid");
				return;
			}
			
			if( dbserverConf.dbms == null ) {
				String[] sa = dbserverConf.url.split( ":" ) ;
				if( sa.length < 3 ) {
					System.out.println( "dbserverConf.url["+dbserverConf.dbms+"] invalid" );
					return;
				}
				
				dbserverConf.dbms = sa[1] ;
			}
			
			if( ! dbserverConf.dbms.equals("mysql") ) {
				System.out.println( "dbserverConf.dbms["+dbserverConf.dbms+"] not support" );
				return;
			}
			
			System.out.println( "--- dbserverConf ---" );
			System.out.println( "  dbms["+dbserverConf.dbms+"]" );
			System.out.println( "driver["+dbserverConf.driver+"]" );
			System.out.println( "   url["+dbserverConf.url+"]" );
			System.out.println( "  user["+dbserverConf.user+"]" );
			System.out.println( "   pwd["+dbserverConf.pwd+"]" );
			
			System.out.println( "--- sqlactionConf ---" );
			System.out.println( " database["+sqlactionConf.database+"]" );
			for( SqlActionTableConf tc : sqlactionConf.tables ) {
				System.out.println( "    table["+tc.table+"]" );
				for( String s : tc.sqlactions ) {
					System.out.println( "sqlaction["+s+"]" );
				}
			}
			
			// Query database metadata
			Class.forName( dbserverConf.driver );
			conn = DriverManager.getConnection( dbserverConf.url, dbserverConf.user, dbserverConf.pwd ) ;
			
			database = new SqlActionDatabase() ;
			database.databaseName = sqlactionConf.database ;
			
			nret = SqlActionTable.GetAllTablesInDatabase( dbserverConf, sqlactionConf, conn, database ) ;
			if( nret != 0 ) {
				System.out.println("*** ERROR : SqlActionTable.GetAllTablesInDatabase failed["+nret+"]");
				conn.close();
				return;
			} else {
				System.out.println("SqlActionTable.GetAllTablesInDatabase ok");
			}
			
			conn.close();
			
			// Show all databases and tables and columns and indexes
			nret = SqlActionTable.TravelAllTables( dbserverConf, sqlactionConf, database.tableList, 1 ) ;
			if( nret != 0 ) {
				System.out.println("*** ERROR : SqlActionTable.TravelAllTables failed["+nret+"]");
				return;
			} else {
				System.out.println("SqlActionTable.TravelAllTables ok");
			}
			
			// Generate class code
			for( SqlActionTableConf tc : sqlactionConf.tables ) {
				StringBuilder out = new StringBuilder() ;
				
				SqlActionTable table = SqlActionTable.FindTable( database.tableList, tc.table ) ;
				if( table == null ) {
					System.out.println( "table["+tc.table+"] not found in database["+sqlactionConf.database+"]" );
					return;
				}
				
				out.append( "package "+sqlactionConf.javaPackage+";\n" );
				out.append( "\n" );
				out.append( "import java.math.*;\n" );
				out.append( "import java.util.*;\n" );
				out.append( "import java.sql.Time;\n" );
				out.append( "import java.sql.Timestamp;\n" );
				out.append( "import java.sql.Connection;\n" );
				out.append( "import java.sql.Statement;\n" );
				out.append( "import java.sql.PreparedStatement;\n" );
				out.append( "import java.sql.ResultSet;\n" );
				out.append( "\n" );
				out.append( "public class "+table.javaClassName+" {\n" );
				
				out.append( "\n" );
				for( SqlActionColumn c : table.columnList ) {
					SqlActionColumn.DumpDefineProperty( c, out );
				}
				
				// Parse sql actions and dump gencode
				for( String sqlaction : tc.sqlactions ) {
					// Parse sql action
					System.out.println( "--- parse sql action --- ["+sqlaction+"]" );
					
					SqlActionSyntaxParser parser = new SqlActionSyntaxParser() ;
					nret = parser.ParseSyntax(sqlaction) ;
					if( nret != 0 ) {
						System.out.println( "SqlActionSyntaxParser.ParseSyntax failed["+nret+"]" );
						return;
					}
					
					if( parser.selectAllColumn == true ) {
						for( SqlActionFromTableToken tt : parser.fromTableTokenList ) {
							for( SqlActionColumn c : table.columnList ) {
								SqlActionSelectColumnToken ct = new SqlActionSelectColumnToken() ;
								ct.tableName = tt.tableName ;
								ct.tableAliasName = tt.tableAliasName ;
								ct.column = c ;
								ct.columnName = c.columnName ;
								parser.selectColumnTokenList.add( ct );
							}
						}
					}
					
					// Postpro parser I
					System.out.println( "--- postpro parser I --- ["+sqlaction+"]" );
					
					for( SqlActionSelectColumnToken ct : parser.selectColumnTokenList ) {
						if( ct.column == null ) {
							if( ct.tableAliasName != null ) {
								if( parser.isFromTableNameExist(ct.tableAliasName) ) {
									ct.tableName = ct.tableAliasName ;
								} else {
									ct.tableName = parser.FindFromTableFromAliasName(ct.tableAliasName) ;
									if( ct.tableName == null ) {
										System.out.println( "tableAliasName["+ct.tableAliasName+"] not found in sqlaction["+sqlaction+"] at SELECT" );
										return;
									}
								}
								
								SqlActionTable otherTable = SqlActionTable.FindTable( database.tableList, ct.tableName ) ;
								if( otherTable == null ) {
									System.out.println( "otherTable["+ct.tableName+"] not found in database["+sqlactionConf.database+"] at SELECT" );
									return;
								}
								
								ct.column = SqlActionColumn.FindColumn( otherTable.columnList, ct.columnName ) ;
								if( ct.column == null ) {
									System.out.println( "column["+ct.columnName+"] not found in table["+ct.tableName+"] in sqlaction["+sqlaction+"] at SELECT" );
									return;
								}
							} else {
								ct.column = SqlActionColumn.FindColumn( table.columnList, ct.columnName ) ;
								if( ct.column == null ) {
									System.out.println( "column["+ct.columnName+"] not found in table["+table.tableName+"] in sqlaction["+sqlaction+"] at SELECT" );
									return;
								}
							}
						}
					}
					
					for( SqlActionWhereColumnToken ct : parser.whereColumnTokenList ) {
						if( ct.column == null ) {
							if( ct.tableAliasName != null ) {
								if( parser.isFromTableNameExist(ct.tableAliasName) ) {
									ct.tableName = ct.tableAliasName ;
								} else {
									ct.tableName = parser.FindFromTableFromAliasName(ct.tableAliasName) ;
									if( ct.tableName == null ) {
										System.out.println( "tableAliasName["+ct.tableAliasName+"] not found in sqlaction["+sqlaction+"] at WHERE" );
										return;
									}
								}
								
								SqlActionTable otherTable = SqlActionTable.FindTable( database.tableList, ct.tableName ) ;
								if( otherTable == null ) {
									System.out.println( "otherTable["+ct.tableName+"] not found in database["+sqlactionConf.database+"] at WHERE" );
									return;
								}
								
								ct.column = SqlActionColumn.FindColumn( otherTable.columnList, ct.columnName ) ;
								if( ct.column == null ) {
									System.out.println( "column["+ct.columnName+"] not found in table["+ct.tableName+"] in sqlaction["+sqlaction+"] at WHERE" );
									return;
								}
							} else {
								ct.column = SqlActionColumn.FindColumn( table.columnList, ct.columnName ) ;
								if( ct.column == null ) {
									System.out.println( "column["+ct.columnName+"] not found in table["+table.tableName+"] in sqlaction["+sqlaction+"] at WHERE" );
									return;
								}
							}
						}
					}
					
					// Show parser result
					System.out.println( "--- show parser result --- ["+sqlaction+"]" );
					
					System.out.println( "selectAllColumn["+parser.selectAllColumn+"]" );
					for( SqlActionSelectColumnToken ct : parser.selectColumnTokenList ) {
						System.out.println( "selectColumnToken.tableName["+ct.tableName+"] .tableAliasName["+ct.tableAliasName+"] .columnName["+ct.columnName+"]" );
					}
					for( SqlActionFromTableToken ct : parser.fromTableTokenList ) {
						System.out.println( "fromTableToken.tableName["+ct.tableName+"] .tableAliasName["+ct.tableAliasName+"]" );
					}
					System.out.println( "insertTableName["+parser.insertTableName+"]" );
					System.out.println( "updateTableName["+parser.updateTableName+"]" );
					for( SqlActionSetColumnToken ct : parser.setColumnTokenList ) {
						System.out.println( "setColumnToken.tableName["+ct.tableName+"] .column["+ct.columnName+"] .columnValue["+ct.columnValue+"]" );
					}
					System.out.println( "deleteTableName["+parser.deleteTableName+"]" );
					for( SqlActionWhereColumnToken ct : parser.whereColumnTokenList ) {
						System.out.println( "whereColumnToken.tableName["+ct.tableName+"] .columnName["+ct.columnName+"] .operator["+ct.operator+"] .columnValue["+ct.columnValue+"]" );
					}
					
					// Postpro parser II
					System.out.println( "--- postpro parser II --- ["+sqlaction+"]" );
					
					for( SqlActionSelectColumnToken ct : parser.selectColumnTokenList ) {
						if( ct.column == null ) {
							if( ct.tableName != null && ! ct.tableName.equalsIgnoreCase(table.tableName) ) {
								SqlActionTable otherTable = SqlActionTable.FindTable( database.tableList, ct.tableName ) ;
								if( otherTable == null ) {
									System.out.println( "otherTable["+ct.tableName+"] not found in database["+sqlactionConf.database+"] at SELECT" );
									return;
								}
								
								ct.column = SqlActionColumn.FindColumn( otherTable.columnList, ct.columnName ) ;
								if( ct.column == null ) {
									System.out.println( "column["+ct.columnName+"] not found in table["+ct.tableName+"] in sqlaction["+sqlaction+"] at SELECT" );
									return;
								}
							} else {
								ct.column = SqlActionColumn.FindColumn( table.columnList, ct.columnName ) ;
								if( ct.column == null ) {
									System.out.println( "column["+ct.columnName+"] not found in table["+table.tableName+"] in sqlaction["+sqlaction+"] at SELECT" );
									return;
								}
							}
						}
					}
					
					for( SqlActionSetColumnToken ct : parser.setColumnTokenList ) {
						if( ct.column == null ) {
							if( ct.tableName != null && ! ct.tableName.equalsIgnoreCase(table.tableName) ) {
								SqlActionTable otherTable = SqlActionTable.FindTable( database.tableList, ct.tableName ) ;
								if( otherTable == null ) {
									System.out.println( "otherTable["+ct.tableName+"] not found in database["+sqlactionConf.database+"] at SET" );
									return;
								}
								
								ct.column = SqlActionColumn.FindColumn( otherTable.columnList, ct.columnName ) ;
								if( ct.column == null ) {
									System.out.println( "column["+ct.columnName+"] not found in table["+ct.tableName+"] in sqlaction["+sqlaction+"] at SET" );
									return;
								}
							} else {
								ct.column = SqlActionColumn.FindColumn( table.columnList, ct.columnName ) ;
								if( ct.column == null ) {
									System.out.println( "column["+ct.columnName+"] not found in table["+table.tableName+"] in sqlaction["+sqlaction+"] at SET" );
									return;
								}
							}
						}
					}
					
					for( SqlActionFromTableToken ct : parser.fromTableTokenList ) {
						if( ct.table == null ) {
							ct.table = SqlActionTable.FindTable( database.tableList, ct.tableName ) ;
							if( ct.table == null ) {
								System.out.println( "table["+ct.tableName+"] not found in database["+sqlactionConf.database+"] at FROM" );
								return;
							}
						}
					}
					
					for( SqlActionWhereColumnToken ct : parser.whereColumnTokenList ) {
						if( ct.column == null ) {
							if( ct.tableName != null && ! ct.tableName.equalsIgnoreCase(table.tableName) ) {
								SqlActionTable otherTable = SqlActionTable.FindTable( database.tableList, ct.tableName ) ;
								if( otherTable == null ) {
									System.out.println( "otherTable["+ct.tableName+"] not found in database["+sqlactionConf.database+"] at WHERE" );
									return;
								}
								
								ct.column = SqlActionColumn.FindColumn( otherTable.columnList, ct.columnName ) ;
								if( ct.column == null ) {
									System.out.println( "column["+ct.columnName+"] not found in table["+ct.tableName+"] in sqlaction["+sqlaction+"] at WHERE" );
									return;
								}
							} else {
								ct.column = SqlActionColumn.FindColumn( table.columnList, ct.columnName ) ;
								if( ct.column == null ) {
									System.out.println( "column["+ct.columnName+"] not found in table["+table.tableName+"] in sqlaction["+sqlaction+"] at WHERE" );
									return;
								}
							}
						}
					}
					
					// Dump gencode
					System.out.println( "--- dump gencode --- ["+sqlaction+"]" );
					
					if( parser.selectColumnTokenList != null && parser.selectColumnTokenList.size() > 0 ) {
						nret = SelectSqlDumpGencode( dbserverConf, sqlactionConf, tc, sqlaction, parser, database, table, out ) ;
						if( nret != 0 ) {
							System.out.println( "*** ERROR : SelectSqlDumpGencode failed["+nret+"]" );
							return;
						} else {
							System.out.println( "SelectSqlDumpGencode ok" );
						}
					} else if( parser.insertTableName != null ) {
						nret = InsertSqlDumpGencode( dbserverConf, sqlactionConf, tc, sqlaction, parser, database, table, out ) ;
						if( nret != 0 ) {
							System.out.println( "*** ERROR : InsertSqlDumpGencode failed["+nret+"]" );
							return;
						} else {
							System.out.println( "InsertSqlDumpGencode ok" );
						}
					} else if( parser.updateTableName != null ) {
						nret = UpdateSqlDumpGencode( dbserverConf, sqlactionConf, tc, sqlaction, parser, database, table, out ) ;
						if( nret != 0 ) {
							System.out.println( "*** ERROR : UpdateSqlDumpGencode failed["+nret+"]" );
							return;
						} else {
							System.out.println( "UpdateSqlDumpGencode ok" );
						}
					} else if( parser.deleteTableName != null ) {
						nret = DeleteSqlDumpGencode( dbserverConf, sqlactionConf, tc, sqlaction, parser, database, table, out ) ;
						if( nret != 0 ) {
							System.out.println( "*** ERROR : DeleteSqlDumpGencode failed["+nret+"]" );
							return;
						} else {
							System.out.println( "DeleteSqlDumpGencode ok" );
						}
					} else {
						System.out.println( "Action["+sqlaction+"] invalid" );
						return;
					}
				}
				
				out.append( "}\n" );
				
				Files.write( Paths.get(table.javaFileName) , out.toString().getBytes() );
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static int SelectSqlDumpGencode( DbServerConf dbserverConf, SqlActionConf sqlactionConf, SqlActionTableConf sqlactionTableConf, String sqlaction, SqlActionSyntaxParser parser, SqlActionDatabase database, SqlActionTable table, StringBuilder out ) {
		
		StringBuilder		sql = new StringBuilder() ;
		StringBuilder		methodName = new StringBuilder() ;
		StringBuilder		methodParameters = new StringBuilder() ;
		int					nret = 0 ;
		
		sql.append( "SELECT " );
		if( parser.selectAllColumn ) {
			sql.append( "*" );
			methodName.append( "SqlAction_SELECT_ALL" );
		} else {
			methodName.append( "SqlAction_SELECT_" );
			
			for( SqlActionSelectColumnToken ct : parser.selectColumnTokenList ) {
				if( ct != parser.selectColumnTokenList.get(0) ) {
					sql.append( "," );
					methodName.append( "_j_" );
				}
				sql.append( ct.columnName );
				methodName.append( ct.columnName );
			}
		}
		
		sql.append( " FROM " );
		methodName.append( "_FROM_" + table.tableName.replace(',','_') );
		for( SqlActionFromTableToken ct : parser.fromTableTokenList ) {
			if( ct != parser.fromTableTokenList.get(0) ) {
				sql.append( "," );
				methodName.append( "_j_" );
			}
			sql.append( ct.tableName );
			methodName.append( ct.tableName );
		}
		
		methodParameters.append( "Connection conn" );
		for( SqlActionFromTableToken ct : parser.fromTableTokenList ) {
			methodParameters.append( ", List<"+ct.table.javaClassName+"> "+ct.table.javaVarName+"List" );
		}
		for( SqlActionFromTableToken ct : parser.fromTableTokenList ) {
			methodParameters.append( ", List<"+ct.table.javaClassName+"> "+ct.table.javaVarName );
		}
		
		if( parser.whereColumnTokenList.size() > 0 ) {
			sql.append( " WHERE " );
			methodName.append( "_WHERE_" );
			
			for( SqlActionWhereColumnToken ct : parser.whereColumnTokenList ) {
				if( ct != parser.whereColumnTokenList.get(0) ) {
					sql.append( " AND " );
					methodName.append( "_AND_" );
				}
				sql.append( ct.columnName + "=?" );
				methodName.append( ct.columnName + "_" + SqlActionColumn.operatorTo(ct.operator) );
				if( ! ct.columnValue.equals("?") ) {
					methodName.append( "_" + SqlActionUtil.convertToUnderscoreExceptForLetterAndDigit(ct.columnValue) );
				}
			}
		}
		
		out.append( "\n" );
		out.append( "\t" + "// "+sqlaction+"\n" );
		if( parser.whereColumnTokenList.size() > 0 ) {
			out.append( "\t" + "public static int " + methodName.toString() + "( "+methodParameters.toString()+" ) throws Exception {\n" );
			out.append( "\t\t" + "PreparedStatement prestmt = conn.prepareStatement(\""+sql+"\") ;\n" );
			int	columnIndex = 0 ;
			for( SqlActionWhereColumnToken ct : parser.whereColumnTokenList ) {
				columnIndex++;
				if( ct.columnValue.equals("?") ) {
					nret = SqlActionColumn.DumpWhereInputColumn( columnIndex, ct.column, "whereInput."+ct.column.javaPropertyName, out ) ;
				} else {
					nret = SqlActionColumn.DumpWhereInputColumn( columnIndex, ct.column, ct.columnValue.replace('\'','"'), out ) ;
				}
				if( nret != 0 ) {
					System.out.println( "DumpWhereInputColumn["+table.tableName+"]["+ct.columnName+"] failed["+nret+"]" );
					return nret;
				}
			}
			out.append( "\t\t" + "ResultSet rs = prestmt.executeQuery() ;\n" );
		} else {
			out.append( "\t" + "public static int " + methodName.toString() + "( "+methodParameters.toString()+" ) throws Exception {\n" );
			out.append( "\t\t" + "Statement stmt = conn.createStatement() ;\n" );
			out.append( "\t\t" + "ResultSet rs = stmt.executeQuery(\""+sql+"\") ;\n" );
		}
		out.append( "\t\t" + "while( rs.next() ) {\n" );
		for( SqlActionFromTableToken ct : parser.fromTableTokenList ) {
			out.append( "\t\t\t" + ct.table.javaClassName + " "+ct.table.javaVarName+" = new "+ct.table.javaClassName+"() ;\n" );
		}
		if( parser.selectColumnTokenList.size() > 0 ) {
			int	columnIndex = 0 ;
			for( SqlActionSelectColumnToken ct : parser.selectColumnTokenList ) {
				columnIndex++;
				nret = SqlActionColumn.DumpSelectOutputColumn( columnIndex, ct.column, out ) ;
				if( nret != 0 ) {
					System.out.println( "DumpSelectOutputColumn["+table.tableName+"]["+ct.columnName+"] failed["+nret+"]" );
					return nret;
				}
			}
		}
		out.append( "\t\t\t" + "selectOutputList.add(selectOutput);\n" );
		out.append( "\t\t" + "}\n" );
		out.append( "\t\t" + "return selectOutputList.size();\n" );
		out.append( "\t" + "}\n" );
		
		return 0;
	}
	
	public static int InsertSqlDumpGencode( DbServerConf dbserverConf, SqlActionConf sqlactionConf, SqlActionTableConf sqlactionTableConf, String sqlaction, SqlActionSyntaxParser parser, SqlActionDatabase database, SqlActionTable table, StringBuilder out ) {
		
		StringBuilder		sql = new StringBuilder() ;
		StringBuilder		methodName = new StringBuilder() ;
		StringBuilder		methodParameters = new StringBuilder() ;
		int					columnIndex ;
		int					nret = 0 ;
		
		sql.append( "INSERT INTO " + table.tableName + " (" );
		methodName.append( "SqlAction_INSERT_INTO_" + table.tableName );
		
		columnIndex = 0 ;
		for( SqlActionColumn c : table.columnList ) {
			if( c.isAutoIncrement == false ) {
				columnIndex++;
				if( columnIndex > 0 )
					sql.append( "," );
				sql.append( c.columnName );
			}
		}
		
		sql.append( ") VALUES (" );
		
		columnIndex = 0 ;
		for( SqlActionColumn c : table.columnList ) {
			if( c.isAutoIncrement == false ) {
				columnIndex++;
				if( columnIndex > 0 )
					sql.append( "," );
				sql.append( "?" );
			}
		}
		
		sql.append( ")" );
		
		methodParameters.append( "Connection conn, " + table.javaClassName + " " + table.javaVarName );
		
		out.append( "\n" );
		out.append( "\t" + "// "+sqlaction+"\n" );
		out.append( "\t" + "public static int " + methodName.toString() + "( "+methodParameters.toString()+" ) throws Exception {\n" );
		out.append( "\t\t" + "PreparedStatement prestmt = conn.prepareStatement(\""+sql+"\") ;\n" );
		columnIndex = 0 ;
		for( SqlActionColumn c : table.columnList ) {
			if( c.isAutoIncrement == false ) {
				columnIndex++;
				nret = SqlActionColumn.DumpWhereInputColumn( columnIndex, c, "whereInput."+c.javaPropertyName, out ) ;
				if( nret != 0 ) {
					System.out.println( "DumpWhereInputColumn["+table.tableName+"]["+c.columnName+"] failed["+nret+"]" );
					return nret;
				}
			}
		}
		out.append( "\t\t" + "return prestmt.executeUpdate() ;\n" );
		out.append( "\t" + "}\n" );
		
		return 0;
	}
	
	public static int UpdateSqlDumpGencode( DbServerConf dbserverConf, SqlActionConf sqlactionConf, SqlActionTableConf sqlactionTableConf, String sqlaction, SqlActionSyntaxParser parser, SqlActionDatabase database, SqlActionTable table, StringBuilder out ) {
		
		StringBuilder		sql = new StringBuilder() ;
		StringBuilder		methodName = new StringBuilder() ;
		StringBuilder		methodParameters = new StringBuilder() ;
		int					nret = 0 ;
		
		sql.append( "UPDATE " + table.tableName + " SET " );
		methodName.append( "SqlAction_UPDATE_" + table.tableName + "_SET" );
		
		for( SqlActionSetColumnToken ct : parser.setColumnTokenList ) {
			if( ct != parser.setColumnTokenList.get(0) ) {
				sql.append( "," );
				methodName.append( "_j" );
			}
			sql.append( ct.columnName + "=?" );
			methodName.append( "_" + ct.columnName + "_E" );
			if( ! ct.columnValue.equals("?") ) {
				methodName.append( "_" + SqlActionUtil.convertToUnderscoreExceptForLetterAndDigit(ct.columnValue) );
			}
		}
		
		if( parser.whereColumnTokenList.size() > 0 ) {
			sql.append( " WHERE " );
			methodName.append( "_WHERE_" );
			
			for( SqlActionWhereColumnToken ct : parser.whereColumnTokenList ) {
				if( ct != parser.whereColumnTokenList.get(0) ) {
					sql.append( " AND " );
					methodName.append( "_AND_" );
				}
				sql.append( ct.columnName + "=?" );
				methodName.append( ct.columnName + "_" + SqlActionColumn.operatorTo(ct.operator) );
				if( ! ct.columnValue.equals("?") ) {
					methodName.append( "_" + SqlActionUtil.convertToUnderscoreExceptForLetterAndDigit(ct.columnValue) );
				}
			}
		}
		
		if( parser.whereColumnTokenList.size() > 0 ) {
			methodParameters.append( "Connection conn, " + table.javaClassName + " " + table.javaVarName + "ForSetInput, " + table.javaClassName + " " + table.javaVarName + "ForWhereInput" );
		} else {
			methodParameters.append( "Connection conn, " + table.javaClassName + " " + table.javaVarName + "ForSetInput " );
		}
		
		out.append( "\n" );
		out.append( "\t" + "// "+sqlaction+"\n" );
		if( parser.whereColumnTokenList.size() > 0 ) {
			out.append( "\t" + "public static int " + methodName.toString() + "( "+methodParameters.toString()+" ) throws Exception {\n" );
			out.append( "\t\t" + "PreparedStatement prestmt = conn.prepareStatement(\""+sql+"\") ;\n" );
			int	columnIndex = 0 ;
			for( SqlActionSetColumnToken ct : parser.setColumnTokenList ) {
				columnIndex++;
				if( ct.columnValue.equals("?") ) {
					nret = SqlActionColumn.DumpSetInputColumn( columnIndex, ct.column, table.javaVarName+"ForSetInput."+ct.column.javaPropertyName, out ) ;
				} else {
					nret = SqlActionColumn.DumpSetInputColumn( columnIndex, ct.column, ct.columnValue.replace('\'','"'), out ) ;
				}
				if( nret != 0 ) {
					System.out.println( "DumpSetInputColumn[\"+table.tableName+\"][\"+ct.columnName+\"] failed["+nret+"]" );
					return nret;
				}
			}
			for( SqlActionWhereColumnToken ct : parser.whereColumnTokenList ) {
				columnIndex++;
				if( ct.columnValue.equals("?") ) {
					nret = SqlActionColumn.DumpWhereInputColumn( columnIndex, ct.column, table.javaVarName+"ForWhereInput."+ct.column.javaPropertyName, out ) ;
				} else {
					nret = SqlActionColumn.DumpWhereInputColumn( columnIndex, ct.column, ct.columnValue.replace('\'','"'), out ) ;
				}
				if( nret != 0 ) {
					System.out.println( "DumpWhereInputColumn[\"+table.tableName+\"][\"+ct.columnName+\"] failed["+nret+"]" );
					return nret;
				}
			}
			out.append( "\t\t" + "return prestmt.executeUpdate() ;\n" );
		} else {
			out.append( "\t" + "public static int " + methodName.toString() + "( "+methodParameters.toString()+" ) throws Exception {\n" );
			out.append( "\t\t" + "PreparedStatement prestmt = conn.prepareStatement(\""+sql+"\") ;\n" );
			int	columnIndex = 0 ;
			for( SqlActionSetColumnToken ct : parser.setColumnTokenList ) {
				columnIndex++;
				if( ct.columnValue.equals("?") ) {
					nret = SqlActionColumn.DumpSetInputColumn( columnIndex, ct.column, table.javaVarName+"ForSetInput."+ct.column.javaPropertyName, out ) ;
				} else {
					nret = SqlActionColumn.DumpSetInputColumn( columnIndex, ct.column, ct.columnValue.replace('\'','"'), out ) ;
				}
				if( nret != 0 ) {
					System.out.println( "DumpSetInputColumn[\"+table.tableName+\"][\"+ct.columnName+\"] failed["+nret+"]" );
					return nret;
				}
			}
			out.append( "\t\t" + "return prestmt.executeUpdate() ;\n" );
		}
		out.append( "\t" + "}\n" );
		
		return 0;
	}
	
	public static int DeleteSqlDumpGencode( DbServerConf dbserverConf, SqlActionConf sqlactionConf, SqlActionTableConf sqlactionTableConf, String sqlaction, SqlActionSyntaxParser parser, SqlActionDatabase database, SqlActionTable table, StringBuilder out ) {
		
		StringBuilder		sql = new StringBuilder() ;
		StringBuilder		methodName = new StringBuilder() ;
		StringBuilder		methodParameters = new StringBuilder() ;
		int					nret = 0 ;
		
		sql.append( "DELETE FROM " + table.tableName );
		methodName.append( "SqlAction_DELETE_FROM_" + table.tableName );
		
		if( parser.whereColumnTokenList.size() > 0 ) {
			sql.append( " WHERE " );
			methodName.append( "_WHERE_" );
			
			for( SqlActionWhereColumnToken ct : parser.whereColumnTokenList ) {
				if( ct != parser.whereColumnTokenList.get(0) ) {
					sql.append( " AND " );
					methodName.append( "_AND_" );
				}
				sql.append( ct.columnName + "=?" );
				methodName.append( ct.columnName + "_" + SqlActionColumn.operatorTo(ct.operator) );
				if( ! ct.columnValue.equals("?") ) {
					methodName.append( "_" + SqlActionUtil.convertToUnderscoreExceptForLetterAndDigit(ct.columnValue) );
				}
			}
		}
		
		if( parser.whereColumnTokenList.size() > 0 ) {
			methodParameters.append( "Connection conn, " + table.javaClassName + " " + table.javaVarName + "ForWhereInput" );
		} else {
			methodParameters.append( "Connection conn" );
		}
		
		out.append( "\n" );
		out.append( "\t" + "// "+sqlaction+"\n" );
		if( parser.whereColumnTokenList.size() > 0 ) {
			out.append( "\t" + "public static int " + methodName.toString() + "( "+methodParameters.toString()+" ) throws Exception {\n" );
			out.append( "\t\t" + "PreparedStatement prestmt = conn.prepareStatement(\""+sql+"\") ;\n" );
			int	columnIndex = 0 ;
			for( SqlActionWhereColumnToken ct : parser.whereColumnTokenList ) {
				columnIndex++;
				if( ct.columnValue.equals("?") ) {
					nret = SqlActionColumn.DumpWhereInputColumn( columnIndex, ct.column, table.javaVarName+"ForWhereInput."+ct.column.javaPropertyName, out ) ;
				} else {
					nret = SqlActionColumn.DumpWhereInputColumn( columnIndex, ct.column, ct.columnValue.replace('\'','"'), out ) ;
				}
				if( nret != 0 ) {
					System.out.println( "DumpWhereInputColumn[\"+table.tableName+\"][\"+ct.columnName+\"] failed["+nret+"]" );
					return nret;
				}
			}
			out.append( "\t\t" + "return prestmt.executeUpdate() ;\n" );
		} else {
			out.append( "\t" + "public static int " + methodName.toString() + "( "+methodParameters.toString()+" ) throws Exception {\n" );
			out.append( "\t\t" + "PreparedStatement prestmt = conn.prepareStatement(\""+sql+"\") ;\n" );
			int	columnIndex = 0 ;
			for( SqlActionSetColumnToken ct : parser.setColumnTokenList ) {
				columnIndex++;
				if( ct.columnValue.equals("?") ) {
					nret = SqlActionColumn.DumpSetInputColumn( columnIndex, ct.column, table.javaVarName+"ForSetInput."+ct.column.javaPropertyName, out ) ;
				} else {
					nret = SqlActionColumn.DumpSetInputColumn( columnIndex, ct.column, ct.columnValue.replace('\'','"'), out ) ;
				}
				if( nret != 0 ) {
					System.out.println( "DumpWhereInputColumn[\"+table.tableName+\"][\"+ct.columnName+\"] failed["+nret+"]" );
					return nret;
				}
			}
			out.append( "\t\t" + "return prestmt.executeUpdate() ;\n" );
		}
		out.append( "\t" + "}\n" );
		
		return 0;
	}
	
}
