package org.example.mpstlibrary.exception;


public class InvalidTransitionException extends InvalidProtocolException {
    public InvalidTransitionException(String message) {
        super("TRANSITION INVALID | check on and to variables : " + message);
    }
}
