/**
 * <p>Provides an interface to the database servers running on Pioneer players.</p>
 *
 * <p>The {@link org.deepsymmetry.beatlink.dbserver.ConnectionManager} knows how to locate the database servers running
 * on the players, and once started, can establish and share connections to them using the
 * {@link org.deepsymmetry.beatlink.dbserver.ConnectionManager#invokeWithClientSession(int, org.deepsymmetry.beatlink.dbserver.ConnectionManager.ClientTask, java.lang.String)}
 * method.</p>
 *
 * <p>Requests and responses to and from the database servers are structured as messages, encapsulated by the
 * {@link org.deepsymmetry.beatlink.dbserver.Message} class, and these are made up of fields, encapsulated by
 * subclasses of {@link org.deepsymmetry.beatlink.dbserver.Field}. The known message types are found in
 * {@link org.deepsymmetry.beatlink.dbserver.Message.KnownType}</p>
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
 * @author  James Elliott

 */
package org.deepsymmetry.beatlink.dbserver;
