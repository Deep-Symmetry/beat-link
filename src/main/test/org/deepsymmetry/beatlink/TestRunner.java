package org.deepsymmetry.beatlink;

import org.deepsymmetry.beatlink.data.CrateDigger;
import org.deepsymmetry.beatlink.data.MetadataFinder;
import org.deepsymmetry.cratedigger.Database;
import org.deepsymmetry.cratedigger.pdb.RekordboxPdb;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class TestRunner {

    // Convenience integration test
    @Test
    public void TestRunner() throws Exception {

        VirtualRekordbox.getInstance().start();
        VirtualRekordbox.getInstance().addUpdateListener(new DeviceUpdateListener() {
            Database db = new Database(new File("/Users/cprepos/Desktop/PIONEER/rekordbox/export.pdb"));
            @Override
            public void received(DeviceUpdate update) {
                if (update instanceof CdjStatus) {
                    CdjStatus status = (CdjStatus) update;
                } else {

                    StringBuilder sb = new StringBuilder();
                    for (byte b : update.packetBytes) {
                        sb.append(String.format("%02X ", b));
                    }
                    System.out.println(sb);
                }
            }
        });


        MetadataFinder.getInstance().start();
        System.out.println("Started up!");
        try {
            Thread.sleep(600000000);
        } catch (InterruptedException e) {
            System.out.println("Interrupted, exiting.");
        }
    }
}
