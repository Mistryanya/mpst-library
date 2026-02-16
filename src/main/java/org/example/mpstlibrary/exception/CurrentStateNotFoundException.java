package org.example.mpstlibrary.exception;

public class CurrentStateNotFoundException extends IndexOutOfBoundsException{
    public CurrentStateNotFoundException (String message){
        super(message);
    }
}
