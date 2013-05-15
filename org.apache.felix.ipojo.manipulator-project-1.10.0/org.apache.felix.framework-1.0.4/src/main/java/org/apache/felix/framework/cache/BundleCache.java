/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.framework.cache;

import java.io.*;
import java.util.*;

import org.apache.felix.framework.Logger;
import org.apache.felix.framework.util.SecureAction;

/**
 * <p>
 * This class, combined with <tt>BundleArchive</tt>, and concrete
 * <tt>BundleRevision</tt> subclasses, implement the Felix bundle cache.
 * It is possible to configure the default behavior of this class by
 * passing properties into Felix' constructor. The configuration properties
 * for this class are:
 * </p>
 * <ul>
 *   <li><tt>felix.cache.bufsize</tt> - Sets the buffer size to be used by
 *       the cache; the default value is 4096. The integer
 *       value of this string provides control over the size of the
 *       internal buffer of the disk cache for performance reasons.
 *   </li>
 *   <li><tt>felix.cache.dir</tt> - Sets the directory to be used by the
 *       cache as its cache directory. The cache directory is where all
 *       profile directories are stored and a profile directory is where a
 *       set of installed bundles are stored. By default, the cache
 *       directory is <tt>.felix</tt> in the user's home directory. If
 *       this property is specified, then its value will be used as the cache
 *       directory instead of <tt>.felix</tt>. This directory will be created
 *       if it does not exist.
 *   </li>
 *   <li><tt>felix.cache.profile</tt> - Sets the profile name that will be
 *       used to create a profile directory inside of the cache directory.
 *       The created directory will contained all installed bundles associated
 *       with the profile.
 *   </li>
 *   <li><tt>felix.cache.profiledir</tt> - Sets the directory to use as the
 *       profile directory for the bundle cache; by default the profile
 *       name is used to create a directory in the <tt>.felix</tt> cache
 *       directory. If this property is specified, then the cache directory
 *       and profile name properties are ignored. The specified value of this
 *       property is used directly as the directory to contain all cached
 *       bundles. If this property is set, it is not necessary to set the
 *       cache directory or profile name properties. This directory will be
 *       created if it does not exist.
 *   </li>
 * </ul>
 * <p>
 * For specific information on how to configure Felix using system properties,
 * refer to the Felix usage documentation.
 * </p>
 * @see org.apache.felix.framework.util.BundleArchive
**/
public class BundleCache
{
    public static final String CACHE_BUFSIZE_PROP = "felix.cache.bufsize";
    public static final String CACHE_DIR_PROP = "felix.cache.dir";
    public static final String CACHE_PROFILE_DIR_PROP = "felix.cache.profiledir";
    public static final String CACHE_PROFILE_PROP = "felix.cache.profile";

    protected static transient int BUFSIZE = 4096;
    protected static transient final String CACHE_DIR_NAME = ".felix";
    protected static transient final String BUNDLE_DIR_PREFIX = "bundle";

    private Map m_configMap = null;
    private Logger m_logger = null;
    private File m_profileDir = null;
    private BundleArchive[] m_archives = null;

    private static SecureAction m_secureAction = new SecureAction();

    public BundleCache(Logger logger, Map configMap)
        throws Exception
    {
        m_configMap = configMap;
        m_logger = logger;
        initialize();
    }

    /* package */ static SecureAction getSecureAction()
    {
        return m_secureAction;
    }

    public synchronized BundleArchive[] getArchives()
        throws Exception
    {
        return m_archives;
    }

    public synchronized BundleArchive getArchive(long id)
        throws Exception
    {
        for (int i = 0; i < m_archives.length; i++)
        {
            if (m_archives[i].getId() == id)
            {
                return m_archives[i];
            }
        }
        return null;
    }

    public synchronized int getArchiveIndex(BundleArchive ba)
    {
        for (int i = 0; i < m_archives.length; i++)
        {
            if (m_archives[i] == ba)
            {
                return i;
            }
        }
        return -1;
    }

    public synchronized BundleArchive create(
        long id, String location, InputStream is)
        throws Exception
    {
        // Construct archive root directory.
        File archiveRootDir =
            new File(m_profileDir, BUNDLE_DIR_PREFIX + Long.toString(id));

        try
        {
            // Create the archive and add it to the list of archives.
            BundleArchive ba =
                new BundleArchive(m_logger, archiveRootDir, id, location, is);
            BundleArchive[] tmp = new BundleArchive[m_archives.length + 1];
            System.arraycopy(m_archives, 0, tmp, 0, m_archives.length);
            tmp[m_archives.length] = ba;
            m_archives = tmp;
            return ba;
        }
        catch (Exception ex)
        {
            if (m_secureAction.fileExists(archiveRootDir))
            {
                if (!BundleCache.deleteDirectoryTree(archiveRootDir))
                {
                    m_logger.log(
                        Logger.LOG_ERROR,
                        getClass().getName()
                            + ": Unable to delete the archive directory - "
                            + archiveRootDir);
                }
            }
            throw ex;
        }
    }

    public synchronized void remove(BundleArchive ba)
        throws Exception
    {
        if (ba != null)
        {
            // Remove the archive.
            ba.dispose();
            // Remove the archive from the cache.
            int idx = getArchiveIndex(ba);
            if (idx >= 0)
            {
                BundleArchive[] tmp =
                    new BundleArchive[m_archives.length - 1];
                System.arraycopy(m_archives, 0, tmp, 0, idx);
                if (idx < tmp.length)
                {
                    System.arraycopy(m_archives, idx + 1, tmp, idx,
                        tmp.length - idx);
                }
                m_archives = tmp;
            }
        }
    }

    /**
     * Provides the system bundle access to its private storage area; this
     * special case is necessary since the system bundle is not really a
     * bundle and therefore must be treated in a special way.
     * @param fileName the name of the file in the system bundle's private area.
     * @return a <tt>File</tt> object corresponding to the specified file name.
     * @throws Exception if any error occurs.
    **/
    public synchronized File getSystemBundleDataFile(String fileName) throws Exception
    {
        // Make sure system bundle directory exists.
        File sbDir = new File(m_profileDir, BUNDLE_DIR_PREFIX + Long.toString(0));

        // If the system bundle directory exists, then we don't
        // need to initialize since it has already been done.
        if (!getSecureAction().fileExists(sbDir))
        {
            // Create system bundle directory, if it does not exist.
            if (!getSecureAction().mkdirs(sbDir))
            {
                m_logger.log(
                    Logger.LOG_ERROR,
                    getClass().getName() + ": Unable to create system bundle directory.");
                throw new IOException("Unable to create system bundle directory.");
            }
        }

        // Do some sanity checking.
        if ((fileName.length() > 0) && (fileName.charAt(0) == File.separatorChar))
            throw new IllegalArgumentException("The data file path must be relative, not absolute.");
        else if (fileName.indexOf("..") >= 0)
            throw new IllegalArgumentException("The data file path cannot contain a reference to the \"..\" directory.");

        // Return the data file.
        return new File(sbDir, fileName);
    }

    //
    // Static file-related utility methods.
    //

    /**
     * This method copies an input stream to the specified file.
     * @param is the input stream to copy.
     * @param outputFile the file to which the input stream should be copied.
    **/
    protected static void copyStreamToFile(InputStream is, File outputFile)
        throws IOException
    {
        OutputStream os = null;

        try
        {
            os = getSecureAction().getFileOutputStream(outputFile);
            os = new BufferedOutputStream(os, BUFSIZE);
            byte[] b = new byte[BUFSIZE];
            int len = 0;
            while ((len = is.read(b)) != -1)
            {
                os.write(b, 0, len);
            }
        }
        finally
        {
            if (is != null) is.close();
            if (os != null) os.close();
        }
    }

    protected static boolean deleteDirectoryTree(File target)
    {
        if (!getSecureAction().fileExists(target))
        {
            return true;
        }

        if (getSecureAction().isFileDirectory(target))
        {
            File[] files = getSecureAction().listDirectory(target);
            for (int i = 0; i < files.length; i++)
            {
                deleteDirectoryTree(files[i]);
            }
        }

        return getSecureAction().deleteFile(target);
    }

    //
    // Private methods.
    //

    private void initialize() throws Exception
    {
        // Get buffer size value.
        try
        {
            String sBufSize = (String) m_configMap.get(CACHE_BUFSIZE_PROP);
            if (sBufSize != null)
            {
                BUFSIZE = Integer.parseInt(sBufSize);
            }
        }
        catch (NumberFormatException ne)
        {
            // Use the default value.
        }

        // See if the profile directory is specified.
        String profileDirStr = (String) m_configMap.get(CACHE_PROFILE_DIR_PROP);
        if (profileDirStr != null)
        {
            m_profileDir = new File(profileDirStr);
        }
        else
        {
            // Since no profile directory was specified, then the profile
            // directory will be a directory in the cache directory named
            // after the profile.

            // First, determine the location of the cache directory; it
            // can either be specified or in the default location.
            String cacheDirStr = (String) m_configMap.get(CACHE_DIR_PROP);
            if (cacheDirStr == null)
            {
                // Since no cache directory was specified, put it
                // ".felix" in the user's home by default.
                cacheDirStr = System.getProperty("user.home");
                cacheDirStr = cacheDirStr.endsWith(File.separator)
                    ? cacheDirStr : cacheDirStr + File.separator;
                cacheDirStr = cacheDirStr + CACHE_DIR_NAME;
            }

            // Now, get the profile name.
            String profileName = (String) m_configMap.get(CACHE_PROFILE_PROP);
            if (profileName == null)
            {
                throw new IllegalArgumentException(
                    "No profile name or directory has been specified.");
            }
            // Profile name cannot contain the File.separator char.
            else if (profileName.indexOf(File.separator) >= 0)
            {
                throw new IllegalArgumentException(
                    "The profile name cannot contain the file separator character.");
            }

            m_profileDir = new File(cacheDirStr, profileName);
        }

        // Create profile directory, if it does not exist.
        if (!getSecureAction().fileExists(m_profileDir))
        {
            if (!getSecureAction().mkdirs(m_profileDir))
            {
                m_logger.log(
                    Logger.LOG_ERROR,
                    getClass().getName() + ": Unable to create directory: "
                        + m_profileDir);
                throw new RuntimeException("Unable to create profile directory.");
            }
        }

        // Create the existing bundle archives in the profile directory,
        // if any exist.
        List archiveList = new ArrayList();
        File[] children = getSecureAction().listDirectory(m_profileDir);
        for (int i = 0; (children != null) && (i < children.length); i++)
        {
            // Ignore directories that aren't bundle directories or
            // is the system bundle directory.
            if (children[i].getName().startsWith(BUNDLE_DIR_PREFIX) &&
                !children[i].getName().equals(BUNDLE_DIR_PREFIX + Long.toString(0)))
            {
                // Recreate the bundle archive.
                try
                {
                    archiveList.add(new BundleArchive(m_logger, children[i]));
                }
                catch (Exception ex)
                {
                    // Log and ignore.
                    m_logger.log(Logger.LOG_ERROR,
                        getClass().getName() + ": Error creating archive.", ex);
                }
            }
        }

        m_archives = (BundleArchive[])
            archiveList.toArray(new BundleArchive[archiveList.size()]);
    }
}