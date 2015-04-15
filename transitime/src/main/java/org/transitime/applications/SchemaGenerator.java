/* 
 * This file is part of Transitime.org
 * 
 * Transitime.org is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL) as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * Transitime.org is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transitime.org .  If not, see <http://www.gnu.org/licenses/>.
 */
package org.transitime.applications;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.cfg.Configuration;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.tool.hbm2ddl.SchemaExport;

/**
 * For generating SQL schema files based on classes to be stored in database
 * that were annotated for Hibernate. This is much nicer than trying to figure
 * out what the schema really should be by hand. This code was copied from
 * http:/
 * /jandrewthompson.blogspot.nl/2009/10/how-to-generate-ddl-scripts-from.html
 * <p>
 * Note that unfortunately there does not appear to be a way to specify the
 * order of the columns in the resulting create table SQL statements. Though
 * this has been asked for, it appears to still be a limitation of Hibernate.
 * The default ordering appears to be first the @Id columns in reverse
 * alphabetical order, and then the non @Id columns in alphabetical order. Yes,
 * quite peculiar.
 * <p>
 * Since the resulting automatically generated files have unneeded drop commands
 * these are filtered out. This way the resulting sql is smaller and easier to
 * understand.
 * 
 * @author john.thompson and Skibu Smith
 *
 */
public class SchemaGenerator {
	private final Configuration cfg;
	private final String packageName;
	private final String outputDirectory;
	
	/**
	 * MySQL handles fractional seconds differently from PostGRES and other
	 * DBs. Need to use "datetime(3)" for fractional seconds whereas with 
	 * PostGRES can use the default "timestamp" type. In order to handle
	 * this properly in the generated ddl schema files need to not use
	 * @Column(columnDefinition="datetime(3)") in the Java class that defines
	 * the db object. Instead need to use this special ImprovedMySQLDialect
	 * as the Dialect.
	 */
	public static class ImprovedMySQLDialect extends MySQLDialect {
		public ImprovedMySQLDialect() {
			super();
			// Specify special SQL type for MySQL for timestamps so that get
			// fractions seconds.
			registerColumnType(Types.TIMESTAMP, "datetime(3)");
		}
	}


	@SuppressWarnings("unchecked")
	public SchemaGenerator(String packageName, String outputDirectory) throws Exception {
		this.cfg = new Configuration();
		this.cfg.setProperty("hibernate.hbm2ddl.auto", "create");

		for (Class<Object> clazz : getClasses(packageName)) {
			this.cfg.addAnnotatedClass(clazz);
		}
		
		this.packageName = packageName;
		this.outputDirectory = outputDirectory;
	}

	/**
	 * Gets rid of the unwanted drop table commands. These aren't needed because
	 * the resulting script is intended only for creating a database, not for
	 * deleting all the data and recreating the tables.
	 * 
	 * @param outputFilename
	 */
	private void trimCruftFromFile(String outputFilename) {
		// Need to write to a temp file because if try to read and write
		// to same file things get quite confused.
		String tmpFileName = outputFilename + "_tmp";
		
		BufferedReader reader = null;
		BufferedWriter writer = null;
		try {
			FileInputStream fis = new FileInputStream(outputFilename);
			reader = new BufferedReader(new InputStreamReader(fis));

			FileOutputStream fos = new FileOutputStream(tmpFileName);
			writer = new BufferedWriter(new OutputStreamWriter(fos));
			
			String line;
			while ((line = reader.readLine()) != null) {
				// Filter out "drop table" commands
				if (line.contains("drop table")) {
					// Read in following blank line
					line = reader.readLine();
					
					// Continue to next line since filtering out drop table commands
					continue;
				}
				
				// Filter out "drop sequence" oracle commands
				if (line.contains("drop sequence")) {
					// Read in following blank line
					line = reader.readLine();
					
					// Continue to next line since filtering out drop commands
					continue;					
				}
				
				// Filter out the alter table commands where dropping a key or
				// a constraint
				if (line.contains("alter table")) {
					String nextLine = reader.readLine();
					if (nextLine.contains("drop")) {
						// Need to continue reading until process a blank line
						while (reader.readLine().length() != 0);
						
						// Continue to next line since filtering out drop commands
						continue;					
					} else {
						// It is an "alter table" command but not a "drop". 
						// Therefore need to keep this command. Since read in
						// two lines need to handle this specially and then
						// continue
						writer.write(line);
						writer.write("\n");
						writer.write(nextLine);
						writer.write("\n");
						continue;
					}
				}
				
				// Line not being filtered so write it to the file
				writer.write(line);
				writer.write("\n");
			}
		} catch (IOException e) {
			System.err.println("Could not trim cruft from file "
					+ outputFilename + " . " + e.getMessage());
		} finally {
			try {
				if (reader != null)
					reader.close();
				if (writer != null)
					writer.close();
			} catch (IOException e) {
			}
		}

		// Move the temp file to the original name
		try {
			Files.copy(new File(tmpFileName).toPath(),
					new File(outputFilename).toPath(),
					StandardCopyOption.REPLACE_EXISTING);
			Files.delete(new File(tmpFileName).toPath());
		} catch (IOException e) {
			System.err.println("Could not rename file " + tmpFileName + " to "
					+ outputFilename);
		}

	}
	
	/**
	 * Method that actually creates the file.
	 * 
	 * @param dbDialect to use
	 */
	private void generate(Dialect dialect) {
		cfg.setProperty("hibernate.dialect", dialect.getDialectClass());

		SchemaExport export = new SchemaExport(cfg);
		export.setDelimiter(";");
		
		// Determine file name. Use "ddl_" plus dialect name such as mysql or
		// oracle plus the package name with "_" replacing "." such as
		// org_transitime_db_structs .
		String packeNameSuffix = 
				packageName.replace(".", "_");
		String outputFilename = (outputDirectory!=null?outputDirectory+"/" : "") + 
				"ddl_" + dialect.name().toLowerCase() + 
				"_" + packeNameSuffix + ".sql";
		
		export.setOutputFile(outputFilename);
		
		// Export, but only to an SQL file. Don't actually modify the database
		System.out.println("Writing file " + outputFilename);
		export.execute(true, false, false, false);
		
		// Get rid of unneeded SQL for dropping tables and keys and such
		trimCruftFromFile(outputFilename);
	}

	/**
	 * Utility method used to fetch Class list based on a package name.
	 * 
	 * @param packageName
	 *            (should be the package containing your annotated beans.
	 */
	@SuppressWarnings("rawtypes")
	private List<Class> getClasses(String packageName) throws Exception {
		List<Class> classes = new ArrayList<Class>();
		File directory = null;
		try {
			ClassLoader cld = Thread.currentThread().getContextClassLoader();
			if (cld == null) {
				throw new ClassNotFoundException("Can't get class loader.");
			}
			String path = packageName.replace('.', '/');
			URL resource = cld.getResource(path);
			if (resource == null) {
				throw new ClassNotFoundException("No resource for " + path);
			}
			directory = new File(resource.getFile());
		} catch (NullPointerException x) {
			throw new ClassNotFoundException(packageName + " (" + directory
					+ ") does not appear to be a valid package");
		}
		if (directory.exists()) {
			for (String fileName : directory.list()) {
				if (fileName.endsWith(".class")) {
					// removes the .class extension
					String className = packageName + '.'
							+ fileName.substring(0, fileName.indexOf(".class"));
					classes.add(Class.forName(className));
				}
			}
		} else {
			throw new ClassNotFoundException(packageName
					+ " is not a valid package");
		}

		return classes;
	}

	/**
	 * Holds the class names of hibernate dialects for easy reference.
	 */
	private static enum Dialect {
		ORACLE("org.hibernate.dialect.Oracle10gDialect"), 
		// Note that using special ImprovedMySqlDialect
		MYSQL("org.transitime.applications.SchemaGenerator$ImprovedMySQLDialect"),
		POSTGRES("org.hibernate.dialect.PostgreSQLDialect"),
		HSQL("org.hibernate.dialect.HSQLDialect");

		private String dialectClass;

		private Dialect(String dialectClass) {
			this.dialectClass = dialectClass;
		}

		public String getDialectClass() {
			return dialectClass;
		}
	}

	/**
	 * Param args args[0] is the package name for the Hibernate annotated
	 * classes whose schema is to be exported such as
	 * "org.transitime.db.structs". args[1] is optional output directory where
	 * the resulting files are to go. If the optional output directory is not
	 * specified then schema files written to local directory.
	 * <p>
	 * The resulting files have the name "ddl_" plus dialect name such as mysql
	 * or oracle plus the first two components of the package name such as
	 * org_transitime.
	 */
	public static void main(String[] args) throws Exception {
		final String packageName = args[0];
		final String outputDirectory = args.length > 1 ? args[1] : null;
		
		// Note: need to use separate SchemaGenerator objects for each
		// dialect because for some reason they otherwise interfere
		// with each other.
		SchemaGenerator gen = new SchemaGenerator(packageName, outputDirectory);
		gen.generate(Dialect.POSTGRES);
		
		gen = new SchemaGenerator(packageName, outputDirectory);
		gen.generate(Dialect.ORACLE);

		gen = new SchemaGenerator(packageName, outputDirectory);
		gen.generate(Dialect.MYSQL);
	}

}
