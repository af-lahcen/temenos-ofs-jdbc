package org.t24.driver;


import java.sql.SQLException;


class T24Exception extends SQLException 
{
	T24Exception(){
    	super();
	}

	T24Exception(String s){
    	super(s);
	}

	T24Exception(String s,Throwable cause){
    	super(s);
    	this.initCause(cause);
	}


} 
