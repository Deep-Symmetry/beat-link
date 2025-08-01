# beat-link

[![project chat](https://img.shields.io/badge/chat-on%20zulip-brightgreen)](https://deep-symmetry.zulipchat.com/#narrow/stream/275322-beat-link-trigger)
<img align="right" width="275" height="250" alt="Beat Link"
      src="assets/BeatLink-logo-padded-left.png">

A Java library for synchronizing with beats from Pioneer DJ Link
equipment, and finding out details about the tracks that are playing.

See
[beat-link-trigger](https://github.com/Deep-Symmetry/beat-link-trigger#beat-link-trigger) and
[beat-carabiner-java](https://github.com/Deep-Symmetry/beat-carabiner-java)
for examples of what you can do with this.

[![License](https://img.shields.io/github/license/Deep-Symmetry/beat-link?color=blue)](#licenses)


## Installing

Beat-link is available through Maven Central, so to use it in your
Maven project, all you need is to include the appropriate dependency.

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.deepsymmetry/beat-link/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.deepsymmetry/beat-link)

Click the **maven central** badge above to view the repository entry
for beat-link. The proper format for including the latest release as a
dependency in a variety of tools, including Leiningen if you are using
beat-link from Clojure, can be found in the **Dependency Information**
section.

Beat link uses [slf4j](https://www.slf4j.org/manual.html) to allow you
to integrate it with whatever Java logging framework your project is
using, so you will need to include the appropriate slf4j binding on
your class path.

It also uses the Deep Symmetry projects
[electro](https://github.com/Deep-Symmetry/electro) (to synchronize
with the Ableton Link timeline) and
[crate-digger](https://github.com/Deep-Symmetry/crate-digger) (to
download and parse rekordbox database files from players).

If you want to manually install beat-link, you can download the
library from the
[releases](https://github.com/Deep-Symmetry/beat-link/releases) page and
put it on your project&rsquo;s class path, along with
[electro](https://github.com/Deep-Symmetry/electro/releases),
[crate-digger](https://github.com/Deep-Symmetry/crate-digger/releases),
[`slf4j-api.jar`](https://www.slf4j.org/download.html) and the slf4j
binding to the logging framework you would like to use.

![Create library jar](https://github.com/Deep-Symmetry/beat-link/workflows/Create%20library%20jar/badge.svg)

You will also need
[Remote Tea](https://sourceforge.net/projects/remotetea/) for creating and parsing old-version NFS packets, so Maven
is by far your easiest bet, because it will take care of _all_ these
libraries for you.

## Compatibility

This is in no way a sanctioned implementation of the protocols. It should be clear, but:

> [!WARNING]
> Use at your own risk! For example, there are reports that
> the XDJ-RX (and XDJ-RX2) crash when BLT starts, so don&rsquo;t use
> it with one on your network. As Pioneer themselves
> [explain](https://forums.pioneerdj.com/hc/en-us/community/posts/203113059-xdj-rx-as-single-deck-on-pro-dj-link-),
> the XDJ-RX does not actually implement the protocol:
>
> &ldquo;The LINK on the RX (and RX2) is ONLY for linking to rekordbox
> on your computer or a router with Wi-Fi to connect rekordbox mobile.
> It can not exchange LINK data with other CDJs or DJMs.&rdquo;

While these techniques appear to work for us so far, there are many
gaps in our knowledge, and things could change at any time with new
releases of hardware or even firmware updates from Pioneer.

> [!CAUTION]
> You should also not expect to be able to run Beat Link, or any
> project like it, on the same machine that you are running rekordbox,
> because they will compete over access to network ports. Also, as
> noted above, the XDJ-RX and XDJ-RX2 do not support the full DJ Link
> protocol, and so will not work with software like this. And, of
> course, controllers which don't even have Link (Ethernet) jacks
> can't work either.

> [!TIP]
> :white_check_mark: Beat Link seems to work great with CDJ-2000 Nexus and nxs2 gear, release 7.3.0 began to support CDJ-3000 features, and even more are supported starting in version 8.
> It works fairly well (with less information available) with older CDJ-2000s.
> It has also been reported to work with XDJ-1000 gear, and (starting with version 0.6.0) with the XDJ-XZ as well.
> If you can try it with anything else, *please* let us know what you learn in [Beat Link Trigger's Zulip channel](https://deep-symmetry.zulipchat.com/#narrow/stream/275322-beat-link-trigger).
> If you have worked out actionable details about something that could be improved, after discussing it on Zulip, [open an Issue](https://github.com/Deep-Symmetry/beat-link/issues) or submit a pull request so we can all improve our understanding together.
> 
> :construction: We have added experimental support for the Opus Quad, which, despite not supporting a DJ-Link network, seems to communicate with rekordbox to support lighting in a way that will allow us to offer good support in Beat Link once we reorganize the code to support working that way.
> Thanks to [@cprepos](https://github.com/cprepos) for doing most of this work!
> 
> In later releases of version 8 we will see if we can take that further, and also identify what is practical in working with the XDJ-AZ.

If something isn’t working with your hardware, and you don’t yet know the details why, but are willing to learn a little and help figure it out, look at the [dysentery project](https://github.com/Deep-Symmetry/dysentery#dysentery), which is where we organize the research tools and results which made programs like Beat Link possible.

## Getting Help

<a href="https://zulip.com"><img align="right" alt="Zulip logo"
 src="assets/zulip-icon-circle.svg" width="128" height="128"></a>

Deep Symmetry’s projects are generously sponsored with hosting by [Zulip](https://zulip.com), an open-source modern team chat app designed to keep both live and asynchronous conversations organized.
Thanks to them, you can [chat with our community](https://deep-symmetry.zulipchat.com/#narrow/stream/275322-beat-link-trigger), ask questions, get inspiration, and share your own ideas.

For more details, see [Get Help](GET_HELP.adoc).

## Usage

See the [API Documentation](https://deepsymmetry.org/beatlink/apidocs/)
for full details, but here is a nutshell guide:

### Finding Devices

The [`DeviceFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/DeviceFinder.html)
class allows you to find DJ Link devices on your network. To activate it:

```java
import org.deepsymmetry.beatlink.DeviceFinder;

// ...

  DeviceFinder.getInstance().start();
```

After a second, it should have heard from all the devices, and you can
obtain the list of them by calling:

```java
  DeviceFinder.getInstance.getCurrentDevices();
```

This returns a list of [`DeviceAnnouncement`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/DeviceAnnouncement.html)
objects describing the devices that were heard from. To find out
immediately when a new device is noticed, or when an existing device
disappears, you can use
[`DeviceFinder.getInstance().addDeviceAnnouncementListener()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/DeviceFinder.html#addDeviceAnnouncementListener-org.deepsymmetry.beatlink.DeviceAnnouncementListener-).

### Responding to Beats

The [`BeatFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/BeatFinder.html)
class can notify you whenever any DJ Link devices on your network report
the start of a new beat:

```java
import org.deepsymmetry.beatlink.BeatFinder;

// ...

  BeatFinder.getInstance().addBeatListener(new BeatListener() {
      @Override
      public void newBeat(Beat beat) {
         // Your code here...
      }
  });

  BeatFinder.getInstance().start();
```

The [`Beat`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/Beat.html)
object you receive will contain useful information about the state of the
device (such as tempo) at the time of the beat.

> To fully understand how to respond to the beats, you will want to start
> [`VirtualCdj`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html)
> as described in the next section, so it can tell you important
> details about the states of all the players, such as which one is
> the current tempo master. Once that is running, you can pass the `Beat`
> object to [`VirtualCdj.getLatestStatusFor(beat)`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#getLatestStatusFor-org.deepsymmetry.beatlink.DeviceUpdate-)
> and get back the most recent status update received from the device
> reporting the beat.

With just the `Beat` object you can call
[`getBpm()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/Beat.html#getBpm--)
to learn the track BPM when the beat occurred,
[`getPitch()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/Beat.html#getPitch--)
to learn the current pitch (speed) of the player at that moment, and
[`getEffectiveTempo()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/Beat.html#getEffectiveTempo--)
to find the combined effect of pitch and track BPM&mdash;the beats per minute
currently being played. You can also call
[`getBeatWithinBar()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/Beat.html#getBeatWithinBar--)
to see where this beat falls within a measure of music.

If the `VirtualCdj` is active, you can also call the `Beat` object&rsquo;s
[`isTempoMaster()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/Beat.html#isTempoMaster--)
method to find out if it was sent by the device that is currently in
control of the master tempo. (If `VirtualCdj` was not started, this
method will throw an `IllegalStateException`.)

### Getting Device Details

To find some kinds of information, like which device is the tempo master,
how many beats of a track have been played, or how many beats there are
until the next cue point in a track, and any detailed information about
the tracks themselves, you need to have beat-link create a virtual
player on the network. This causes the other players to send detailed status
updates directly to beat-link, so it can interpret and keep track of
this information for you.

```java
import org.deepsymmetry.beatlink.VirtualCdj;

// ...

    VirtualCdj.getInstance().start();
```

> The Virtual player is normally created using an unused device number
> in the range 5&ndash;15 and the name `beat-link`, and announces its
> presence every second and a half. These values can be changed with
> [`setDeviceNumber()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#setDeviceNumber-byte-),
> [`setUseStandardPlayerNumber()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#setUseStandardPlayerNumber-boolean-),
> [`setDeviceName()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#setDeviceName-java.lang.String-),
> and
> [`setAnnounceInterval()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#setAnnounceInterval-int-).

As soon as it is running, you can pass any of the device announcements returned by
[`DeviceFinder.getCurrentDevices()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/DeviceFinder.html#getCurrentDevices--)
to [`getLatestStatusFor(device)`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#getLatestStatusFor-org.deepsymmetry.beatlink.DeviceAnnouncement-)
and get back the most recent status update received from that device.
The return value will either be a
[`MixerStatus`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/MixerStatus.html)
or a [`CdjStatus`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html),
containing a great deal of useful information.

> As described [above](#responding-to-beats), you can do the same thing
> with the `Beat` objects returned by [`BeatFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/BeatFinder.html).

In addition to asking about individual devices, you can find out which
device is the current tempo master by calling
[`getTempoMaster()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#getTempoMaster--),
and learn the current master tempo by calling
[`VirtualCdj.getMasterTempo()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#getMasterTempo--).

If you want to be notified when either of these values change, or whenever
the player that is the current tempo master starts a new beat, you can use
[`addMasterListener()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#addMasterListener-org.deepsymmetry.beatlink.MasterListener-).

If you are building an interface that wants to display as much detail
as possible, you can request every device status update
as soon as it is received, using
[`addUpdateListener()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#addUpdateListener-org.deepsymmetry.beatlink.DeviceUpdateListener-).

### Player Remote Control

In addition to learning about what is happening on other players, the
`VirtualCdj` can ask them to do a limited number of things. You can
tell them to load a track from any media currently mounted in a player
by calling
[`sendLoadTrackCommand()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#sendLoadTrackCommand-int-int-int-org.deepsymmetry.beatlink.CdjStatus.TrackSourceSlot-org.deepsymmetry.beatlink.CdjStatus.TrackType-).
You can cause them to start playing (if they are currently at the cue
position), or stop playing and return to the cue position, by calling
[`sendFaderStartCommand()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#sendFaderStartCommand-java.util.Set-java.util.Set-).

You can tell players to turn Sync mode on or off by calling
[`sendSyncModeCommand()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#sendSyncModeCommand-int-boolean-),
and if there is no DJM on the network you can tell them that their
channels are on or off the air by sending
[`sendOnAirCommand()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#sendOnAirCommand-java.util.Set-).
(You can do that even with a DJM present, but your command will
quickly be overridden by the next one sent by the DJM.)

### Requesting Media Slot Details

You can ask the `VirtualCdj` to find out details about the media
mounted in a player's media slot by calling
[`sendMediaQuery()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#sendMediaQuery-org.deepsymmetry.beatlink.data.SlotReference-).
In order to receive the response, you have to previously have
registered a
[`MediaDetailsListener`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/MediaDetailsListener.html)
instance using
[`addMediaDetailsListener()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#addMediaDetailsListener-org.deepsymmetry.beatlink.MediaDetailsListener-);
in general it's easier to just let the `MetadataFinder` take care of
this for you as described [below](#media-details).

### Sending Status

As of version 0.4.0, the `VirtualCdj` can be configured to send status
packets of its own, so that it can take over the role of tempo master,
and even send beat packets to synchronize other CDJs with a musical
timeline of your choice. For this to work, it must be using a standard
player number in the range 1&ndash;4, configured before you start it as
described [above](#getting-device-details).

> The only way to be able to use the features that rely on sending
> status is to configure the `VirtualCdj` to use a device number in
> the range 1 to 4, like an actual CDJ, using
> `VirtualCdj.getInstance().setUseStandardPlayerNumber()` as described
> [above](#getting-device-details). You can only do that if you are
> using fewer than 4 CDJs, because you need to use a number that is
> not being used by any actual CDJ.

As long as that is true, you can turn on this feature by calling
[`setSendingStatus()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#setSendingStatus-boolean-).

Once it is sending status, you can adjust details about what it sends
by calling
[`setTempo()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#setTempo-double-),
[`setSynced()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#setSynced-boolean-),
[`setOnAir()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#setOnAir-boolean-),
and
[`jumpToBeat()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#jumpToBeat-int-).
To simulate playing a track, call
[`setPlaying()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#setPlaying-boolean-).
During simulated playback, beat packets will be sent at appropriate times
for the current tempo, and the beat number will advance appropriately.
You can find the current simulated playback time by calling
[`getPlaybackPosition()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#getPlaybackPosition--).

### Appointing the Tempo Master

As long as the `VirtualCdj` is sending status packets, you can tell
players to become Tempo Master by calling
[`appointTempoMaster()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#appointTempoMaster-int-),
or have it become the Master itself by calling
[`becomeTempoMaster()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#becomeTempoMaster--).
While acting as tempo master, any tempo changes you make by calling
[`setTempo()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#setTempo-double-)
will be followed by any players that are in Sync mode, and if the
`VirtualCdj` is playing (and thus sending beats) you can call
[`adjustPlaybackPosition()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html#adjustPlaybackPosition-int-)
to keep the players' beat grids aligned with an external sync source
(this is how Beat Link Trigger aligns CDJs with Ableton Link).

In addition to responding to the above functions for setting its own
Sync, On-Air, and Master states, the VirtualCdj respects commands sent
to it by other players or a DJM instructing it to do these things.
While it is in Sync mode, it will stay aligned with the tempo master
like other players do.

### Getting Track Metadata

If you want to be able to retrieve details about loaded tracks, such as
the title, artist, genre, length (in seconds), key, and even artwork
images, start the `MetadataFinder`, which will also start the
`VirtualCdj` if it is not already running.

```java
import org.deepsymmetry.beatlink.data.MetadataFinder;

// ...

    MetadataFinder.getInstance().start();
```

> :warning: The only way to reliably retrieve metadata using the
> `MetadataFinder` on its own is to configure the `VirtualCdj` to use
> a device number in the range 1 to 4, like an actual CDJ, using
> `VirtualCdj.getInstance().setUseStandardPlayerNumber()` as described
> [above](#getting-device-details). You can only do that if you are
> using fewer than 4 CDJs, because you need to use a number that is
> not being used by any actual CDJ. If you are using 4 actual CDJs,
> you will need to leave the `VirtualCdj` using its default number of
> 5, but that means the `MetadataFinder` will often be unable to talk
> to the `dbserver` ports on the players.
>
> As of version 0.5.0, we finally have a good solution to this
> problem, which is to start up the `CrateDigger` class, which uses a
> completely separate mechanism for obtaining metadata, by downloading
> the entire rekordbox database export and track analysis files from
> the NFSv2 server that is also running in the players. The NFSv2
> server is stateless, does not care what player number we are using,
> and can be used no matter how many players are on the network. So,
> in addition to the above code, also do this:

```java
import org.deepsymmetry.beatlink.data.CrateDigger;

// ...

    CrateDigger.getInstance().start();
```

Once the `MetadataFinder`and `CrateDigger` are running, you can access
all the metadata for currently-loaded tracks by calling
[`MetadataFinder.getInstance().getLoadedTracks()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/MetadataFinder.html#getLoadedTracks--),
which returns a `Map` from deck references (player numbers and hot cue
numbers) to
[`TrackMetadata`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/TrackMetadata.html)
objects describing the track currently loaded in that player slot. You
can also call [`MetadataFinder.getLatestMetadataFor(int
player)`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/MetadataFinder.html#getLatestMetadataFor-int-)
to find the metadata for the track loaded in the playback deck of the
specified player. See the
[`TrackMetadata`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/TrackMetadata.html)
API documentation for all the details it provides.

With the `MetadataFinder` running, you can also start the `ArtFinder`,
and use its [`getLoadedArt()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/ArtFinder.html#getLoadedArt--)
or [`getLatestArtFor()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/ArtFinder.html#getLatestArtFor-int-)
methods to get the artwork images associated with the tracks, if there
are any.

### Getting Other Track Information

You can use objects in the
[`org.deepsymmetry.beatlink.data`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/package-summary.html)
package to get beat grids and track waveform data, and even to create
Swing UI components to display the preview and detailed waveform,
reflecting the current playback position.

To illustrate the kind of interface that you can now put
together from the elements offered by Beat Link, here is the Player
Status window from Beat Link Trigger:

<img src="assets/PlayerStatus.png" alt="Player Status window" width="599">

### Media Details

While the `MetadataFinder` is running, it also keeps track of which
players have media mounted in their slots. You can access that
information by calling
[`getMountedMediaSlots()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/MetadataFinder.html#getMountedMediaSlots--).
If you want to know immediately when slots mount and unmount, you can
register a
[`MountListener`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/MountListener.html)
using
[`addMountListener()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/MetadataFinder.html#addMountListener-org.deepsymmetry.beatlink.data.MountListener-).
You can also find
[useful information](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/MediaDetails.html)
about any mounted media database by calling
[`getMountedMediaDetails()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/MetadataFinder.html#getMountedMediaDetails--)
or
[`getMediaDetailsFor()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/MetadataFinder.html#getMediaDetailsFor-org.deepsymmetry.beatlink.data.SlotReference-).

### Loading Menus

You can explore the hierarchy of menus served by a player for one of
its mounted media slots using the
[`MenuLoader`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/MenuLoader.html),
starting with
[`requestRootMenuFrom()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/MenuLoader.html#requestRootMenuFrom-org.deepsymmetry.beatlink.data.SlotReference-int-)
to determine what menus are actually available.

> :warning: The only way to get menus is by talking to the `dbserver`
> on the players, so even if you are using the `CrateDigger` as
> described [above](#getting-track-metadata), you will only be able to
> do this reliably if you are using a real player number, which means
> you can have no more than three actual players on the network.
>
> Also note that in order to
> reliably send the correct message to obtain the root menu for a
> particular slot, the `MenuLoader` needs to know the type of database
> that is mounted in that slot, so the `MetadataFinder` should be
> running and tracking media details for it to check.

## Recognizing Tracks

If you want to know when a particular track has been loaded on a player,
independent of the specific rekordbox export media it was loaded from,
you can start the
[`SignatureFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/SignatureFinder.html),
and it will do most of the work for you, with the help of the
`MetadataFinder`, `WaveformFinder`, and `BeatGridFinder`. Whenever a
rekordbox track is loaded and metadata for it has been obtained, it will
compute an SHA-1 hash of the track title, artist, duration, detailed
waveform and beat grid. The results boil down to a 40-character string
of hexadecimal digits which can be used to uniquely identify and
recognize that track (for example, using it as a key in a hash map to
find cues that should run when that track is playing).

You can access the signatures of all loaded rekordbox tracks by calling
[`getSignatures()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/SignatureFinder.html#getSignatures--),
or look up the signature for a track on a specific player with
[`getLatestSignatureFor()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/SignatureFinder.html#getLatestSignatureFor-int-).
If you want to know immediately when signatures are available for loaded
tracks, you can register a [`SignatureListener`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/SignatureListener.html)
using [`addSignatureListener()`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/SignatureFinder.html#addSignatureListener-org.deepsymmetry.beatlink.data.SignatureListener-).

## An Example

Here is the source for `Example.java`, a small class that demonstrates
how to watch for changes related to the tempo master (and also shows
that printing
[`DeviceUpdate`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/DeviceUpdate.html)
objects can be a useful diagnostic aid):

```java
import java.util.Date;
import org.deepsymmetry.beatlink.*;

public class Example {

    public static void main(String[] args) {
        try {
            VirtualCdj.getInstance().start();
        } catch (java.net.SocketException e) {
            System.err.println("Unable to start VirtualCdj: " + e);
        }

        VirtualCdj.getInstance().addMasterListener(new MasterListener() {
                        @Override
                        public void masterChanged(DeviceUpdate update) {
                            System.out.println("Master changed at " + new Date() + ": " + update);
                        }

                        @Override
                        public void tempoChanged(double tempo) {
                            System.out.println("Tempo changed at " + new Date() + ": " + tempo);
                        }

                        @Override
                        public void newBeat(Beat beat) {
                            System.out.println("Master player beat at " + new Date() + ": " + beat);
                        }
            });

        try {
            Thread.sleep(60000);
        } catch (InterruptedException e) {
            System.out.println("Interrupted, exiting.");
        }
    }
}
```

Compiling and running this class (with the beat-link and slf4j jars on
the class path) watches and reports on tempo master activity for one
minute, producing output like this:

```
> java -cp .:beat-link.jar:slf4j-api-1.7.21.jar:slf4j-simple-1.7.21.jar Example
Master changed at Sun May 08 20:49:23 CDT 2016: CDJ status: Device 2,
 name: DJ-2000nexus, busy? true, pitch: +0.00%, track: 5, track BPM:
 128.0, effective BPM: 128.0, beat: 55, beat within bar: 3,
 cue: --.-, Playing? true, Master? true, Synced? true, On-Air? true
Tempo changed at Sun May 08 20:49:23 CDT 2016: 128.0
Tempo changed at Sun May 08 20:49:47 CDT 2016: 127.9359130859375
    [... lines omitted ...]
Tempo changed at Sun May 08 20:49:51 CDT 2016: 124.991943359375
Tempo changed at Sun May 08 20:49:51 CDT 2016: 124.927978515625
Master changed at Sun May 08 20:49:55 CDT 2016: CDJ status: Device 3,
 name: DJ-2000nexus, busy? true, pitch: -0.85%, track: 4, track BPM:
 126.0, effective BPM: 124.9, beat: 25, beat within bar: 1,
 cue: 31.4, Playing? true, Master? true, Synced? true, On-Air? true
```

## Research

This project is being developed with the help of
[dysentery](https://github.com/Deep-Symmetry/dysentery). Check that
out for details of the packets and protocol, and for ways you can help
figure out more. You can also view [Snapshot API
Documentation](https://deepsymmetry.org/beatlink/snapshot/apidocs/) to
see what is available in the latest preview release.

### Funding

Beat Link is, and will remain, completely free and open-source. If it
has helped you, taught you something, or inspired you, please let us
know and share some of your discoveries and how you are using it! If
you'd like to financially support its ongoing development, you are
welcome (but by no means obligated) to donate to offset the hundreds
of hours of research, development, and writing that have already been
invested. Or perhaps to facilitate future efforts, tools, toys, and
time to explore.

<a href="https://liberapay.com/deep-symmetry/donate"><img align="center" alt="Donate using Liberapay"
    src="https://liberapay.com/assets/widgets/donate.svg"></a> using Liberapay, or
<a href="https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=J26G6ULJKV8RL"><img align="center"
    alt="Donate" src="https://www.paypalobjects.com/en_US/i/btn/btn_donate_SM.gif"></a> using PayPal

> If enough people jump on board, we may even be able to get a newer
> CDJ to experiment with, although that's an unlikely stretch goal.
> :grinning:

### Contributing in Other Ways

First of all, we would *love* to hear from you! We have no way of
knowing who has discovered, explored, downloaded and tried Beat Link
Trigger. So if you have, please write a quick note on the [Zulip
stream](https://deep-symmetry.zulipchat.com/#narrow/stream/275322-beat-link-trigger)
to let us know! Even if it is only to explain why it didn’t
quite work for you.

If you run into specific problems or have ideas of ways Beat Link Trigger could be better, and people in the chat community have suggested it is time to take this step, you can also [open an Issue](https://github.com/Deep-Symmetry/beat-link-trigger/issues) .

And if you think you've come up with new or better way to do something, terrific! Please take a look at our [guidelines for contributing,](CONTRIBUTING.md) and we can’t wait to hear from you!

> Please be mindful of our [Code of Conduct](CODE_OF_CONDUCT.md) to make
> sure everyone feels welcome in the community.


## Licenses

<a href="https://deepsymmetry.org"><img align="right" alt="Deep Symmetry"
 src="assets/DS-logo-github.png" width="250" height="150"></a>

Copyright © 2016–2025 [Deep Symmetry, LLC](https://deepsymmetry.org)

Distributed under the [Eclipse Public License
2.0](https://opensource.org/licenses/EPL-2.0). By using this software
in any fashion, you are agreeing to be bound by the terms of this
license. You must not remove this notice, or any other, from this
software. A copy of the license can be found in
[LICENSE.md](LICENSE.md) within this project.

### Library Licenses

[Remote Tea](https://sourceforge.net/projects/remotetea/),
used for communicating with the NFSv2 servers on players,
is licensed under the [GNU Library General
Public License, version 2](https://opensource.org/licenses/LGPL-2.0).

The [Kaitai Struct](https://kaitai.io) Java runtime, used for parsing
rekordbox exports and media analysis files, is licensed under the
[MIT License](https://opensource.org/licenses/MIT).
