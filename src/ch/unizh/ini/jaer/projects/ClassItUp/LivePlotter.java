/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package ch.unizh.ini.jaer.projects.ClassItUp;

/**
 *
 * @author tobi
 */
/*
import org.LiveGraph.dataFile.write.DataStreamWriter;
import org.LiveGraph.dataFile.write.DataStreamWriterFactory;
import org.LiveGraph.settings.*;
import org.LiveGraph.LiveGraph;
*/
import java.io.File;

public class LivePlotter implements Plotter {

    Network NN;
    //NumberReader NumDisp;       // Numberreader object

    // Properties-Implementation Related
    /*
    DataStreamWriter see;       // DataStreamWriter object
    DataFileSettings dfs;
    LiveGraph app;
*/

    @Override public void init(Network N)
    {
        // Initiallize Buffer-File
        /*
                String dir=System.getProperty("user.dir")+"\\LiveGraph";

                new File(dir).mkdir();

                see=DataStreamWriterFactory.createDataWriter(dir,"NetOutput");
                see.setSeparator(";");
                see.writeFileInfo("Network Output File");
                see.addDataSeries("Crossiness");
                see.addDataSeries("Xiness");

                // Initialize Settings thing
                //dfs = new DataFileSettings();
                //dfs.setDataFile(dir+"\\data.csv");
                //dfs.setUpdateFrequency(1000);
                //dfs.save(dir+"\\startup.lgdfs");

                // Initialize App
                app = LiveGraph.application();
                app.execStandalone();
                //app.execStandalone(new String[] {"-dfs", dir+"\\arrr\\startup.lgdfs"});

*/
    }

    @Override public void update()
    {
 /*      see.setDataValue(NN.N[NN.N.length-2].get_vmem());
        see.setDataValue(NN.N[NN.N.length-1].get_vmem());
        see.writeDataSet();
        if (see.hadIOException()) {
            see.getIOException().printStackTrace();
            see.resetIOException();
          }
*/
    }



}
