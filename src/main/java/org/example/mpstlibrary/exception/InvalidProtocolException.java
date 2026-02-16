package org.example.mpstlibrary.exception;

import java.io.InvalidObjectException;

public class InvalidProtocolException extends InvalidObjectException {

    public InvalidProtocolException (String message){
        super(message);
    }
}
