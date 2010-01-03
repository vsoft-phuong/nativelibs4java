package com.nativelibs4java.runtime.templates;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import org.apache.velocity.*;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.app.VelocityEngine;

/**
 * Generates source code with velocity templates
 * @goal compile
 * @execute phase=generate-sources
 * @description Generates source code with velocity templates
 */
public class TemplatesMojo
    extends AbstractMojo
{
	/**
     * @parameter
     * @optional
     */
    private Map<Object, Object> parameters;
	
	/**
     * @parameter
     * @required
     */
    private String[] resources;
	
    /**
     * Output directory for generated sources.
     * @parameter expression="${project.build.directory}/generated-sources/main/velocity"
     * @optional
     */
    private File outputDirectory;

    /**
     * @parameter expression="${project}"
     * @required
     * @readonly
     * @since 1.0
     */
    private MavenProject project;

    public File getOutputDirectory() {
		return outputDirectory;
	}

    public void execute()
        throws MojoExecutionException
    {
        VelocityEngine ve;
        try {
            ve = new VelocityEngine();
            ve.setProperty("resource.loader", "class");
            ve.setProperty("class.resource.loader.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
            ve.init();
            //Velocity.init();
            //System.out.println("VELOCITY PARAMETERS = " + parameters);
		} catch (Exception ex) {
            throw new MojoExecutionException("Failed to initialize Velocity", ex);
        }
        /*Map transformedParams = new HashMap(parameters.size());
        for (Map.Entry e : parameters.entrySet()) {
            Object key = e.getKey();
            Object v = e.getValue();
            if (v.equals("true"))
                v = Boolean.TRUE;
            else if (v.equals("false"))
                v = Boolean.FALSE;

            transformedParams.put(key, v);
        }*/
        for (String resource : resources) {
			try {
                org.apache.velocity.Template template = //Velocity.getTemplate(resource);
                    ve.getTemplate(resource);
                
				VelocityContext context = new VelocityContext(parameters);
			
				StringWriter out = new StringWriter();
				template.merge(context, out);
				out.close();

				File outFile = null;
				Object s = context.get("outputFile");
				if (s != null)
					outFile = new File(s.toString());
				else {
					s = context.get("relativeOutputFile");
					if (s != null)
						outFile = new File(getOutputDirectory(), s.toString());
					else {
						getLog().info("No 'outputFile' nor 'relativeOutputFile' variable defined. Using template resource name.");
						outFile = new File(getOutputDirectory(), resource);
					}
				}
				outFile.getParentFile().mkdirs();

				getLog().info("Writing template '" + resource + "' to '" + outFile + "'");
				

				FileWriter f = new FileWriter(outFile);
				f.write(out.toString());
				f.close();
				
			} catch (Exception ex) {
				throw new MojoExecutionException("Failed to execute template '" + resource + "'", ex);
			}
		}
        
		project.addCompileSourceRoot(outputDirectory.toString());
    }

}