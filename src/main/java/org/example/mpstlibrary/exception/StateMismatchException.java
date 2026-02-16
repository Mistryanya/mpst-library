package org.example.mpstlibrary.exception;

import java.util.InputMismatchException;

public class StateMismatchException extends InputMismatchException {
    public StateMismatchException (String message){
        super("STATE MISMATCHED: " + message);
    }
}
