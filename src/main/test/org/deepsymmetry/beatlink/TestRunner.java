package org.deepsymmetry.beatlink;

import org.deepsymmetry.beatlink.data.*;
import org.deepsymmetry.cratedigger.Database;
import org.deepsymmetry.cratedigger.pdb.RekordboxPdb;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class TestRunner {

    // Convenience integration test
    @Test
    public void TestRunner() throws Exception {
        DeviceFinder.getInstance().start();
        System.out.println(DeviceFinder.getInstance().getCurrentDevices());
        VirtualCdj.getInstance().start((byte)0x01);
        MetadataFinder.getInstance().start();
        CrateDigger.getInstance().start();
        BeatFinder.getInstance().start();
//
//        DeviceFinder.getInstance().addDeviceAnnouncementListener(new DeviceAnnouncementListener() {
//            @Override
//            public void deviceFound(DeviceAnnouncement announcement) {
//                System.out.println(DeviceFinder.getInstance().getCurrentDevices());
//                System.out.println(announcement);
//                System.out.println("found");
//            }
//
//            @Override
//            public void deviceLost(DeviceAnnouncement announcement) {
//
//            }
//        });
//
//
//
//
//
//        UpdateSocketConnection.getInstance().addUpdateListener(new DeviceUpdateListener() {
//            private Database database = new Database(new File("/Users/cprepos/Desktop/PIONEER/rekordbox/export.pdb"));
//
//            @Override
//            public void received(DeviceUpdate update) {
//                if (update instanceof CdjStatus) {
//                    int trackNumber = ((CdjStatus) update).getTrackNumber();
//                    RekordboxPdb.TrackRow track = database.trackIndex.get((long)trackNumber);
//                    System.out.println(update);
//                    System.out.println(database.getText(track.title()));
//                    System.out.println(track.artistId());
//                    System.out.println(track.keyId());
//                    System.out.println(trackNumber);
//                    System.out.println(((CdjStatus) update).getTrackSourcePlayer());
//                    System.out.println(((CdjStatus) update).isPlayingForwards());
//                    System.out.println(((CdjStatus) update).isPlayingBackwards());
//                }
//            }
//
//        });

//        MetadataFinder.getInstance().addTrackMetadataListener(new TrackMetadataListener() {
//            @Override
//            public void metadataChanged(TrackMetadataUpdate update) {
//
//                System.out.println(update);
//            }
//        });

        System.out.println(MetadataFinder.getInstance().getLoadedTracks());

        System.out.println("Started up!");
        try {
            Thread.sleep(600000);
        } catch (InterruptedException e) {
            System.out.println("Interrupted, exiting.");
        }
    }
}
