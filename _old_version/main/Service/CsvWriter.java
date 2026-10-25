
package com.example.websocket.websocket_Data_Connection.Service;
import com.example.websocket.websocket_Data_Connection.model.stockUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;

public class CsvWriter {

    public void read(String message)
    {
        try
        {
            ObjectMapper mapper=new ObjectMapper();
            stockUpdate stock=mapper.readValue(message,stockUpdate.class);
        }
        catch(Exception e)
        {
              System.out.println("error "+e);
        }
    }

    public void write(stockUpdate update)
    {
        FileWriter ob = null;
        PrintWriter pFile=null;
        try{

                ob=new FileWriter("stock_data.csv",true);
                pFile=new PrintWriter(ob);
                // toPlainString avoids scientific notation (e.g. "4.0E-4") for small volumes
                pFile.println(update.getSymbol()+","+update.getPrice()+","+update.getTime()+","
                        +BigDecimal.valueOf(update.getVolume()).toPlainString());
        }

        catch(Exception e)
        {
            System.out.println(e);

        }
        finally
        {
            try {
                if(ob!=null) {
                    ob.close();
                    assert pFile != null;
                    pFile.close();
                }
            }
            catch(Exception e2)
            {
              System.out.println("file could not be closed "+e2);
            }

        }


    }

}
