/*
 * Copyright (C) 2019 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.deliverablesanalyzer.utils;

import static org.apache.commons.vfs2.FileName.SEPARATOR;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.vfs2.FileContent;
import org.apache.commons.vfs2.FileObject;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.ObjectBasedValueSource;
import org.codehaus.plexus.interpolation.PrefixAwareRecursionInterceptor;
import org.codehaus.plexus.interpolation.PrefixedObjectValueSource;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

public final class MavenUtils {

    private static final Pattern POM_XML_PATTERN = Pattern.compile(
            String.join(SEPARATOR, "^", "META-INF", "maven", "(?<groupId>.*)", "(?<artifactId>.*)", "pom.xml$"));

    private static final String POM_EXTENSION = "pom";

    private static final Pattern MAVEN_PROPERTY_PATTERN = Pattern.compile(".*\\$\\{.*}.*");

    private MavenUtils() {
        // Prevent instantiation
    }

    /**
     * Determines whether the given file object is a POM file. A pom file is a file that has extension <code>pom</code>.
     *
     * @param fileObject the file object
     * @return true if the file object is a POM file and false otherwise
     */
    public static boolean isPom(FileObject fileObject) {
        return POM_EXTENSION.equals(fileObject.getName().getExtension());
    }

    /**
     * Determines whether the given file object is a POM file inside a JAR file. This method returns <code>true</code>
     * for a file inside a jar named <code>pom.xml</code> if it is inside the <code>META-INF/maven</code> directory.
     *
     * @param fileObject the file object
     * @return true if the file object is a POM file and false otherwise
     */
    public static boolean isPomXml(FileObject fileObject) {
        String path = fileObject.getName().getPath();
        Matcher matcher = POM_XML_PATTERN.matcher(path);
        return matcher.matches();
    }

    public static MavenProject getMavenProject(FileObject pomFileObject)
            throws InterpolationException, IOException, XmlPullParserException {
        try (FileContent content = pomFileObject.getContent(); InputStream in = content.getInputStream()) {
            MavenXpp3Reader reader = new MavenXpp3Reader();

            try {
                Model model = reader.read(in);
                String groupId = model.getGroupId();
                String artifactId = model.getArtifactId();
                String version = model.getVersion();
                model.setGroupId(interpolateString(model, groupId));
                model.setArtifactId(interpolateString(model, artifactId));
                model.setVersion(interpolateString(model, version));
                List<License> licenses = model.getLicenses();

                for (License license : licenses) {
                    license.setName(interpolateString(model, license.getName()));
                    license.setUrl(interpolateString(model, license.getUrl()));
                    license.setDistribution(interpolateString(model, license.getDistribution()));
                    license.setComments(interpolateString(model, license.getComments()));
                }

                return new MavenProject(model);
            } catch (IOException e) {
                throw new XmlPullParserException(e.getMessage());
            }
        }
    }

    private static String interpolateString(Model model, String input) throws InterpolationException {
        if (input != null && MAVEN_PROPERTY_PATTERN.matcher(input).matches()) {
            StringSearchInterpolator interpolator = new StringSearchInterpolator();
            List<String> possiblePrefixes = List.of("pom.", "project.");
            PrefixedObjectValueSource prefixedObjectValueSource = new PrefixedObjectValueSource(
                    possiblePrefixes,
                    model,
                    false);
            Properties properties = model.getProperties();
            PropertiesBasedValueSource propertiesBasedValueSource = new PropertiesBasedValueSource(properties);
            ObjectBasedValueSource objectBasedValueSource = new ObjectBasedValueSource(model);
            interpolator.addValueSource(prefixedObjectValueSource);
            interpolator.addValueSource(propertiesBasedValueSource);
            interpolator.addValueSource(objectBasedValueSource);
            return interpolator.interpolate(input, new PrefixAwareRecursionInterceptor(possiblePrefixes));
        }

        return input;
    }
}
