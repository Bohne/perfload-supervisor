/*
 * Copyright (c) 2013 mgm technology partners GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mgmtp.perfload.supervisor

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.IOUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import ch.ethz.ssh2.ChannelCondition
import ch.ethz.ssh2.Connection
import ch.ethz.ssh2.SCPClient
import ch.ethz.ssh2.Session

/**
 * Utility class for executing SSH commands.
 *
 * @author rnaegele
 */
class SshUtils {
	private static final Logger LOG = LoggerFactory.getLogger(SshUtils.class)

	/**
	 * Creates an SSH connection, which may be used concurrently by multiple threads.
	 *
	 * @param host the host
	 * @param user the user
	 * @param password the password
	 * @return the connection
	 */
	public static Connection createConnection(String host, String user, String password) {
		Connection conn = new Connection(host)
		conn.connect(null, 10000, 10000)
		LOG.info("Successfully established SSH connection to '{}'.", host)
		if (conn.authenticateWithPassword(user, password)) {
			LOG.info("Successfully authenticated SSH connection to '{}'.", host)
			return conn
		}
		throw new IllegalStateException("SSH connection to '$host' could not be established.")
	}

	/**
	 * Creates an SSH connection using public key authentication, which may be used concurrently
	 * by multiple threads.
	 *
	 * @param host the host
	 * @param user the user
	 * @param pemfile a file containing a DSA or RSA private key of the user in OpenSSH key format
	 * @param password the password (only used if the PEM structure is encrypted)
	 * @return the connection
	 */
	public static Connection createConnection(String host, String user, File pemFile, String password) {
		Connection conn = new Connection(host)
		conn.connect(null, 10000, 10000)
		LOG.info("Successfully established SSH connection to '{}'.", host)
		if (conn.authenticateWithPublicKey(user, pemFile, password)) {
			LOG.info("Successfully authenticated SSH connection to '{}' using PEM file: {}", host, pemFile)
			return conn
		}
		throw new IllegalStateException("SSH connection to '$host' could not be established.")
	}

	/**
	 * Executes an SSH command using the given connection.
	 *
	 * @param conn the SSH connection
	 * @param command the SSH command
	 * @param an optional timeout, default is no timeout
	 * @return the exit code of the remote command
	 */
	public static Integer executeCommand(Connection conn, String command, long timeout = 0L) {
		Session sess = conn.openSession()
		try {
			sess.execCommand(command)

			InputStream stdout = sess.getStdout()
			InputStream stderr = sess.getStderr()

			while (true) {
				if ((stdout.available() == 0) && (stderr.available() == 0)) {
					// Even though currently there is no data available, it may be that new data arrives and the session's
					// underlying channel is closed before we call waitForCondition(). This means that EOF and
					// STDOUT_DATA (or STDERR_DATA, or both) may be set together.

					int conditions = sess.waitForCondition(ChannelCondition.STDOUT_DATA | ChannelCondition.STDERR_DATA | ChannelCondition.EOF, timeout)

					if ((conditions & ChannelCondition.TIMEOUT) != 0) {
						LOG.warn('SSH connection timed out after {} ms.', timeout)
						break
					}

					// Here we do not need to check separately for CLOSED, since CLOSED implies EOF
					if ((conditions & ChannelCondition.EOF) != 0) {
						// The remote side won't send us further data...

						if ((conditions & (ChannelCondition.STDOUT_DATA | ChannelCondition.STDERR_DATA)) == 0) {
							// ... and we have consumed all data in the local arrival window.
							break
						}
					}
				}

				Thread th = Thread.start {
					while (stdout.available() > 0) {
						stdout.withReader {
							LOG.info(it.readLine())
						}
					}

					while (stderr.available() > 0) {
						stderr.withReader {
							LOG.info(it.readLine())
						}
					}
				}
				// We need to obey a possible timeout. Otherwise this might block forever.
				th.join(timeout)
			}

			return sess.getExitStatus()
		} finally {
			sess.close()
		}
	}

	/**
	 * Executes an SSH command creating a new SSH connection using the given credentials.
	 *
	 * @param host the host to connect to
	 * @param user the user
	 * @param password the password
	 * @param command the SSH command
	 * @param an optional timeout, default is no timeout
	 * @return the exit code of the remote command
	 */
	public static Integer executeCommand(String host, String user, String password, String command, long timeout = 0L) {
		Connection conn = createConnection(host, user, password)
		try {
			return executeCommand(conn, command, timeout)
		} finally {
			conn.close()
		}
	}

	/**
	 * Executes an SSH command creating a new SSH connection using the given pem file.
	 *
	 * @param host the host to connect to
	 * @param user the user
	 * @param pemFile the PEM file
	 * @param password the password
	 * @param command the SSH command
	 * @param an optional timeout, default is no timeout
	 * @return the exit code of the remote command
	 */
	public static Integer executeCommand(String host, String user, File pemFile, String password, String command, long timeout = 0L) {
		Connection conn = createConnection(host, user, pemFile, password)
		try {
			return executeCommand(conn, command, timeout)
		} finally {
			conn.close()
		}
	}

	/**
	 * Downloads a file via SCP using the given connection.
	 *
	 * @param host the host to connect to
	 * @param user the user
	 * @param password the password
	 * @param todir the target directory (must exist on the local file system!)
	 * @param file the path to the file on the remote host
	 */
	public static void scpDownload(Connection conn, String todir, String file) {
		LOG.info("Downloading file '{}' from '{}' via SCP...", file, conn.hostname)

		SCPClient scpClient = new SCPClient(conn)
		OutputStream os = null
		File destFile = new File(todir, FilenameUtils.getName(file))
		boolean ok = false
		try {
			os = new FileOutputStream(destFile)
			scpClient.get(file, os)
			ok = true
		} catch (IOException ex) {
			LOG.error('Error downloading file via SCP: {}', ex)
		} finally {
			IOUtils.closeQuietly(os)
			if (!ok) {
				// an empty corrupt zip file might have been stored
				FileUtils.deleteQuietly(destFile)
			}
		}
	}

	/**
	 * Downloads a file via SCP creating a new SSH connection using the given credentials.
	 *
	 * @param host the host to connect to
	 * @param user the user
	 * @param password the password
	 * @param todir the target directory (must exist on the local file system!)
	 * @param file the path to the file on the remote host
	 */
	public static void scpDownload(String host, String user, String password, String todir, String file) {
		Connection conn = createConnection(host, user, password)
		try {
			scpDownload(conn, todir, file)
		} finally {
			conn.close()
		}
	}

	/**
	 * Downloads a file via SCP creating a new SSH connection using the given credentials.
	 *
	 * @param host the host to connect to
	 * @param user the user
	 * @param pemFile the PEM file
	 * @param password the password
	 * @param todir the target directory (must exist on the local file system!)
	 * @param file the path to the file on the remote host
	 */
	public static void scpDownload(String host, String user, File pemFile, String password, String todir, String file) {
		Connection conn = createConnection(host, user, pemFile, password)
		try {
			scpDownload(conn, todir, file)
		} finally {
			conn.close()
		}
	}
}
