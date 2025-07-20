/**
 * <p>A library for synchronizing with beats from Pioneer DJ Link equipment,
 * and finding out details about the tracks that are playing.</p>
 *
 * <p>Overview and installation instructions are found on the <a href="https://github.com/Deep-Symmetry/beat-link"
 * target="_blank">Project page</a> on GitHub.</p>
 *
 * <p>This top level package provides classes for finding a DJ Link network, watching for devices to appear and
 * disappear on it, and creating a {@link org.deepsymmetry.beatlink.VirtualCdj} which can obtain more detailed
 * information about what other players are doing, like their current tempo, pitch, playback state, which player is
 * the current tempo master, and the source and database ID of the currently-loaded rekordbox track. See the
 * {@link org.deepsymmetry.beatlink.CdjStatus} class for more details.</p>
 *
 * <p>The classes in the {@link org.deepsymmetry.beatlink.data} package can augment this low-level information with
 * rich details about the track metadata, including album art, cue point locations, beat grid, and waveforms, both
 * the whole-track preview, and full detailed waveform for scrolling through. The
 * {@link org.deepsymmetry.beatlink.data.MetadataFinder} is the main coordinator for obtaining and caching this
 * information, and it keeps track of the track metadata for all tracks loaded on decks, either for current playback,
 * or as hot cues.</p>
 *
 * <p>The metadata queries are performed with the help of the {@link org.deepsymmetry.beatlink.dbserver} package,
 * which knows how to locate and communicate with the database servers running on the players.</p>
 *
 * <p>For shows in which four players are in use, the dbserver interface cannot be relied on, since we are not able
 * to use a real player number ourselves. The {@link org.deepsymmetry.beatlink.data.CrateDigger} class allows us to
 * still obtain metadata in those situations, by downloading entire rekordbox database export files from the players
 * using their NFSv2 servers, which do not care about player numbers. By parsing the database it can also find and
 * download the files containing track analysis information like waveforms, beat grids, artwork, etc.</p>
 *
 * <h3>Background</h3>
 *
 * <p>This project is based on research performed with <a href="https://github.com/Deep-Symmetry/dysentery"
 * target="_blank">dysentery</a>, and the <a href="https://djl-analysis.deepsymmetry.org" target="_blank">protocol
 * analysis</a> resulting from that project.</p>
 *
 * <p>Raw database access is provided by the
 * <a href="https://github.com/Deep-Symmetry/crate-digger" target="_blank">Crate Digger</a> project.</p>
 *
 * <p>A good example of an application built using this library is
 * <a href="https://github.com/Deep-Symmetry/beat-link-trigger" target="_blank">Beat Link Trigger</a>.</p>
 *
 * @author  James Elliott
 */
package org.deepsymmetry.beatlink;
