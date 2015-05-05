/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.xbean.classloader;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * @author Dain Sundstrom
 * @author Martin Lippert (changed locking to use ReentrantReadWriteLock)
 */
public class UrlResourceFinder implements ResourceFinder {

	private static final JarFileFactory DEFAULT_JAR_FILE_FACTORY = new JarFileFactory() {
		public JarFile newJarFile(File file) throws IOException {
			return new JarFile(file);
		}
	};

	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private final ReadLock rl = lock.readLock();
	private final WriteLock wl = lock.writeLock();
	
	private final LinkedHashSet<URL> urls = new LinkedHashSet<URL>();
	private final LinkedHashMap<URL, ResourceLocation> classPath = new LinkedHashMap<URL, ResourceLocation>();
	private final LinkedHashSet<File> watchedFiles = new LinkedHashSet<File>();

	private boolean destroyed = false;
	private JarFileFactory jarFileFactory;

	public UrlResourceFinder(JarFileFactory jarFileFactory) {
		this(jarFileFactory, new URL[] {});
	}

	public UrlResourceFinder(JarFileFactory jarFileFactory, URL[] urls) {
		this.jarFileFactory = (jarFileFactory == null ? DEFAULT_JAR_FILE_FACTORY : jarFileFactory);
		addUrls(urls);
	}

	protected final JarFileFactory getJarFileFactory() {
		return jarFileFactory;
	}

	public void destroy() {
		try {
			wl.lock();

			if (destroyed) {
				return;
			}
			destroyed = true;
			urls.clear();
			for (Iterator<ResourceLocation> iterator = classPath.values().iterator(); iterator.hasNext();) {
				ResourceLocation resourceLocation = iterator.next();
				resourceLocation.close();
			}
			classPath.clear();
		}
		finally {
			wl.unlock();
		}
	}

	public ResourceHandle getResource(String resourceName) {
		try {
			rl.lock();

			if (destroyed) {
				return null;
			}
			for (Iterator<Map.Entry<URL, ResourceLocation>> iterator = getClassPath().entrySet().iterator(); iterator
					.hasNext();) {
				Map.Entry<URL, ResourceLocation> entry = iterator.next();
				ResourceLocation resourceLocation = entry.getValue();
				ResourceHandle resourceHandle = resourceLocation.getResourceHandle(resourceName);
				if (resourceHandle != null && !resourceHandle.isDirectory()) {
					return resourceHandle;
				}
			}
		}
		finally {
			rl.unlock();
		}
		return null;
	}

	public URL findResource(String resourceName) {
		try {
			rl.lock();
		
			if (destroyed) {
				return null;
			}
			for (Iterator<Map.Entry<URL, ResourceLocation>> iterator = getClassPath().entrySet().iterator(); iterator
					.hasNext();) {
				Map.Entry<URL, ResourceLocation> entry = iterator.next();
				ResourceLocation resourceLocation = entry.getValue();
				ResourceHandle resourceHandle = resourceLocation.getResourceHandle(resourceName);
				if (resourceHandle != null) {
					return resourceHandle.getUrl();
				}
			}
		}
		finally {
			rl.unlock();
		}
		return null;
	}

	public Enumeration<URL> findResources(String resourceName) {
		try {
			rl.lock();
			return new ResourceEnumeration(new ArrayList<ResourceLocation>(getClassPath().values()), resourceName);
		}
		finally {
			rl.unlock();
		}
	}

	public void addUrl(URL url) {
		addUrls(Collections.singletonList(url));
	}

	public URL[] getUrls() {
		try {
			rl.lock();
			return urls.toArray(new URL[urls.size()]);
		}
		finally {
			rl.unlock();
		}
	}

	/**
	 * Adds an array of urls to the end of this class loader.
	 * @param urls the URLs to add
	 */
	protected void addUrls(URL[] urls) {
		addUrls(Arrays.asList(urls));
	}

	/**
	 * Adds a list of urls to the end of this class loader.
	 * @param urls the URLs to add
	 */
	protected void addUrls(List<URL> urls) {
		try {
			wl.lock();

			if (destroyed) {
				throw new IllegalStateException("UrlResourceFinder has been destroyed");
			}

			boolean shouldRebuild = this.urls.addAll(urls);
			if (shouldRebuild) {
				rebuildClassPath();
			}
		}
		finally {
			wl.unlock();
		}
	}

	private LinkedHashMap<URL, ResourceLocation> getClassPath() {
		try {
			rl.lock();
			
			for (Iterator<File> iterator = watchedFiles.iterator(); iterator.hasNext();) {
				File file = iterator.next();
				if (file.canRead()) {
					rl.unlock();
					wl.lock();
					rebuildClassPath();
					wl.unlock();
					rl.lock();
					break;
				}
			}
		}
		finally {
			rl.unlock();
		}

		return classPath;
	}

	/**
	 * Rebuilds the entire class path. This class is called when new URLs are added or one of the watched files becomes
	 * readable. This method will not open jar files again, but will add any new entries not alredy open to the class
	 * path. If any file based url is does not exist, we will watch for that file to appear.
	 */
	private void rebuildClassPath() {
		assert wl.isHeldByCurrentThread() : "This method can only be called while holding the lock";

		// copy all of the existing locations into a temp map and clear the class path
		Map<URL, ResourceLocation> existingJarFiles = new LinkedHashMap<URL, ResourceLocation>(classPath);
		classPath.clear();

		LinkedList<URL> locationStack = new LinkedList<URL>(urls);
		try {
			while (!locationStack.isEmpty()) {
				URL url = locationStack.removeFirst();

				// Skip any duplicate urls in the claspath
				if (classPath.containsKey(url)) {
					continue;
				}

				// Check is this URL has already been opened
				ResourceLocation resourceLocation = existingJarFiles.remove(url);

				// If not opened, cache the url and wrap it with a resource location
				if (resourceLocation == null) {
					try {
						File file = cacheUrl(url);
						resourceLocation = createResourceLocation(url, file);
					} catch (FileNotFoundException e) {
						// if this is a file URL, the file doesn't exist yet... watch to see if it appears later
						if ("file".equals(url.getProtocol())) {
							File file = new File(url.getPath());
							watchedFiles.add(file);
							continue;

						}
					} catch (IOException ignored) {
						// can't seem to open the file... this is most likely a bad jar file
						// so don't keep a watch out for it because that would require lots of checking
						// Dain: We may want to review this decision later
						continue;
					}
				}

				// add the jar to our class path
				classPath.put(resourceLocation.getCodeSource(), resourceLocation);

				// push the manifest classpath on the stack (make sure to maintain the order)
				List<URL> manifestClassPath = getManifestClassPath(resourceLocation);
				locationStack.addAll(0, manifestClassPath);
			}
		} catch (Error e) {
			destroy();
			throw e;
		}

		for (Iterator<ResourceLocation> iterator = existingJarFiles.values().iterator(); iterator.hasNext();) {
			ResourceLocation resourceLocation = iterator.next();
			resourceLocation.close();
		}
	}

	protected File cacheUrl(URL url) throws IOException {
		if (!"file".equals(url.getProtocol())) {
			// download the jar
			throw new Error("Only local file jars are supported " + url);
		}

		File file;
		try {
			file = new File(url.toURI());
		} catch (URISyntaxException e) {
			file = new File(url.getPath());
		}
		if (!file.exists()) {
			throw new FileNotFoundException(file.getAbsolutePath());
		}
		if (!file.canRead()) {
			throw new IOException("File is not readable: " + file.getAbsolutePath());
		}
		return file;
	}

	protected ResourceLocation createResourceLocation(URL codeSource, File cacheFile) throws IOException {
		if (!cacheFile.exists()) {
			throw new FileNotFoundException(cacheFile.getAbsolutePath());
		}
		if (!cacheFile.canRead()) {
			throw new IOException("File is not readable: " + cacheFile.getAbsolutePath());
		}

		ResourceLocation resourceLocation = null;
		if (cacheFile.isDirectory()) {
			// DirectoryResourceLocation will only return "file" URLs within this directory
			// do not user the DirectoryResourceLocation for non file based urls
			resourceLocation = new DirectoryResourceLocation(cacheFile);
		} else {
			resourceLocation = new JarResourceLocation(codeSource, jarFileFactory.newJarFile(cacheFile));
		}
		return resourceLocation;
	}

	private List<URL> getManifestClassPath(ResourceLocation resourceLocation) {
		try {
			// get the manifest, if possible
			Manifest manifest = resourceLocation.getManifest();
			if (manifest == null) {
				// some locations don't have a manifest
				return Collections.emptyList();
			}

			// get the class-path attribute, if possible
			String manifestClassPath = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
			if (manifestClassPath == null) {
				return Collections.emptyList();
			}

			// build the urls...
			// the class-path attribute is space delimited
			URL codeSource = resourceLocation.getCodeSource();
			LinkedList<URL> classPathUrls = new LinkedList<URL>();
			for (StringTokenizer tokenizer = new StringTokenizer(manifestClassPath, " "); tokenizer.hasMoreTokens();) {
				String entry = tokenizer.nextToken();
				try {
					// the class path entry is relative to the resource location code source
					URL entryUrl = new URL(codeSource, entry);
					classPathUrls.addLast(entryUrl);
				} catch (MalformedURLException ignored) {
					// most likely a poorly named entry
				}
			}
			return classPathUrls;
		} catch (IOException ignored) {
			// error opening the manifest
			return Collections.emptyList();
		}
	}

	/**
	 * Factory interface that is used by the {@link UrlResourceFinder} to create a new {@link JarFile} instance. Allows
	 * for various different {@link JarFile} implementations to be supported by the finder.
	 */
	public static interface JarFileFactory {
		/**
		 * Factory method used to create a {@link JarFile} instance for the specified {@link File}.
		 * @param file The file to read
		 * @return A {@link JarFile} instance, this can also be a specialised subclass of {@link JarFile}.
		 * @throws IOException
		 */
		public JarFile newJarFile(File file) throws IOException;
	}
}
