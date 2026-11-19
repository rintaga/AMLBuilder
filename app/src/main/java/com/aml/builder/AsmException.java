package com.aml.builder;

public class AsmException extends Exception {
    public int line;
    public AsmException(String msg) { super(msg); }
    public AsmException(int line, String msg) { super(msg); this.line = line; }
}
