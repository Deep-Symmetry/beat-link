/**
 * <p>Offers rich information about the tracks loaded in players on the network.</p>
 *
 * <p>The classes in this package can augment the low-level information offered by the packet listeners with
 * rich details about track metadata, including album art, cue point locations, beat grid, and waveforms, both
 * the whole-track preview, and full detailed waveform for scrolling through. The
 * {@link org.deepsymmetry.beatlink.data.MetadataFinder} is the main coordinator for obtaining and caching this
 * information, and it keeps track of the track metadata for all tracks loaded on decks, either for current playback,
 * or as hot cues. It also supports creating metadata cache files to avoid the need to query players for this
 * information during busy shows with a full complement of four CDJs.</p>
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
 * target="_blank">dysentery</a>,
 * and the <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf" target="_blank">packet
 * analysis</a> resulting from that project (also available as
 * <a href="https://github.com/Deep-Symmetry/dysentery/raw/master/doc/Analysis.pdf" target="_blank">downloadable
 * PDF</a>).</p>
 *
 * <p>Raw database access is provided by the
 * <a href="https://github.com/Deep-Symmetry/crate-digger" target="_blank">Crate Digger</a> project.</p>
 *
 * <p>An good example of an application built using this library is
 * <a href="https://github.com/Deep-Symmetry/beat-link-trigger" target="_blank">Beat Link Trigger</a>.</p>
 *
 * @author  James Elliott
 */
package org.deepsymmetry.beatlink.data;
