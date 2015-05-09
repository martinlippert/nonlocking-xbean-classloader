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

import java.net.URL;
import java.util.Collection;

/**
 * A variation of {@link JarFileClassLoader} that never locks underlying JAR files, allowing JARs to be deleted even if
 * the ClassLoader is open.
 *
 * @author Phillip Webb
 */
public class NonLockingJarFileClassLoader extends JarFileClassLoader {
	
	private static final NonLockingJarFileFactory JAR_FILE_FACTORY = new NonLockingJarFileFactory();
	
	public static final void setCheckForUpdates(boolean checkForUpdates) {
		JAR_FILE_FACTORY.setCheckForUpdates(checkForUpdates);
	}
	
	public static final void flushCache() {
		JAR_FILE_FACTORY.flushCache();
	}
	
	public NonLockingJarFileClassLoader(String name, URL[] urls, ClassLoader parent, boolean inverseClassLoading,
			String[] hiddenClasses, String[] nonOverridableClasses) {
		super(name, urls, parent, inverseClassLoading, hiddenClasses, nonOverridableClasses);
	}

	public NonLockingJarFileClassLoader(String name, URL[] urls, ClassLoader parent) {
		super(name, urls, parent);
	}

	public NonLockingJarFileClassLoader(String name, URL[] urls, ClassLoader[] parents, boolean inverseClassLoading,
			Collection<String> hiddenClasses, Collection<String> nonOverridableClasses) {
		super(name, urls, parents, inverseClassLoading, hiddenClasses, nonOverridableClasses);
	}

	public NonLockingJarFileClassLoader(String name, URL[] urls, ClassLoader[] parents, boolean inverseClassLoading,
			String[] hiddenClasses, String[] nonOverridableClasses) {
		super(name, urls, parents, inverseClassLoading, hiddenClasses, nonOverridableClasses);
	}

	public NonLockingJarFileClassLoader(String name, URL[] urls, ClassLoader[] parents) {
		super(name, urls, parents);
	}

	public NonLockingJarFileClassLoader(String name, URL[] urls) {
		super(name, urls);
	}

	protected UrlResourceFinder newResourceFinder() {
		return new UrlResourceFinder(JAR_FILE_FACTORY);
	}
}
