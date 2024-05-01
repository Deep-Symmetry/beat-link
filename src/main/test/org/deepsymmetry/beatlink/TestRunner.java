package org.deepsymmetry.beatlink;

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
        System.out.println("Started up!");
        try {
            Thread.sleep(600000000);
        } catch (InterruptedException e) {
            System.out.println("Interrupted, exiting.");
        }
    }
}
