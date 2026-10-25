package com.example.websocket.websocket_Data_Connection.model;

public class stockUpdate {

    private double p;
    private String s;
    private long t;
    // double, not int: crypto trades are fractional (e.g. 0.0004 BTC), and an int truncated them to 0
    private double v;

    /*
    private list<String> c;
    The c field is optional metadata. It describes why a trade happened (like whether it came from a dark pool, auction, etc).
     For many use cases, beginners skip it because:
     It’s not always needed.

     It makes the model a little more complex (List<String> instead of just numbers/strings).
    */
    public stockUpdate(double price, String symbol,long time,double volume)
    {
        p=price;
        s=symbol;
        t=time;
        v=volume;
    }
    public double getPrice()
    {
        return p;
    }

    public String getSymbol() {
        return s;
    }

    public long getTime() {
        return t;
    }

    public double getVolume() {
        return v;
    }

    public void setP(double p) {
        this.p = p;
    }

    public void setS(String s) {
        this.s = s;
    }

    public void setT(long t) {
        this.t = t;
    }

    public void setV(double v) {
        this.v = v;
    }
}
