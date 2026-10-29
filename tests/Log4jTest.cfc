component extends="org.lucee.cfml.test.LuceeTestCase" labels="log4j" {

	// far apart from each other (26 hours), so the date and the hour differ between them
	variables.configTZ = "Etc/GMT+12";
	variables.requestTZ = "Pacific/Kiritimati";

	function beforeAll() {
		variables.adminCfc = new org.lucee.cfml.Administrator( "server", request.ServerAdminPassword );
		variables.regional = adminCfc.getRegional();
		adminCfc.updateRegional( variables.configTZ, variables.regional.locale );
	}

	function afterAll() {
		adminCfc.updateRegional( variables.regional.timezone, variables.regional.locale );
	}

	function run( testResults, testBox ) {
		describe( "Log4j extension", function() {

			it( title="the extension replaces the simple built-in log engine", body=function( currentSpec ) {
				expect( getApplicationSettings().log4j.version ).notToBe( "simple" );
			});

			it( title="json layout writes one json document per log entry", body=function( currentSpec ) {
				var name = "log4jtest-json-" & createUUID();
				var file = expandPath( "{lucee-config}/logs/#name#.log" );
				try {
					updateLog( name, "json", "resource", { path: file } );
					var token = createUUID();
					writeLog( text="json #token#", log=name, type="information" );
					var content = fileRead( file );
					expect( content ).toInclude( token );
					var json = deserializeJSON( trim( content ) );
					expect( json.level ).toBe( "INFO" );
				}
				finally {
					removeLog( name, file );
				}
			});

			it( title="LDEV-2248 json layout keeps the thread name and writes the thread id and priority as separate keys", body=function( currentSpec ) {
				var name = "log4jtest-json-thread-" & createUUID();
				var file = expandPath( "{lucee-config}/logs/#name#.log" );
				try {
					updateLog( name, "json", "resource", { path: file } );
					var token = createUUID();
					writeLog( text="json thread #token#", log=name, type="information" );
					var json = deserializeJSON( trim( fileRead( file ) ) );
					expect( json.message ).toInclude( token );
					expect( json.thread ).toBeString();
					expect( len( json.thread ) ).toBeGT( 0 );
					expect( json.threadId ).toBeNumeric();
					expect( json.threadPriority ).toBeNumeric();
				}
				finally {
					removeLog( name, file );
				}
			});

			it( title="LDEV-2248 json layout writes an ISO-8601 timestamp and keeps the instant", body=function( currentSpec ) {
				var name = "log4jtest-json-timestamp-" & createUUID();
				var file = expandPath( "{lucee-config}/logs/#name#.log" );
				try {
					updateLog( name, "json", "resource", { path: file } );
					var System = createObject( "java", "java.lang.System" );
					var before = System.currentTimeMillis() - 2000;
					writeLog( text="json timestamp #createUUID()#", log=name, type="information" );
					var after = System.currentTimeMillis() + 2000;
					var json = deserializeJSON( trim( fileRead( file ) ) );
					expect( json.timestamp ).toMatch( "^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$" );
					// the timestamp is in UTC, so it has to be a moment between before and after
					var ts = createObject( "java", "java.time.Instant" ).parse( json.timestamp ).toEpochMilli();
					expect( ts ).toBeGTE( before );
					expect( ts ).toBeLTE( after );
					// the existing time keys stay
					expect( structKeyExists( json, "instant" ) || structKeyExists( json, "timeMillis" ) ).toBeTrue();
				}
				finally {
					removeLog( name, file );
				}
			});

			it( title="LDEV-2248 json layout writes the cause chain inside thrown", body=function( currentSpec ) {
				var name = "log4jtest-json-cause-" & createUUID();
				var file = expandPath( "{lucee-config}/logs/#name#.log" );
				try {
					updateLog( name, "json", "resource", { path: file } );
					var token = createUUID();
					var inner = createObject( "java", "java.lang.IllegalStateException" ).init( "inner #token#" );
					var outer = createObject( "java", "java.lang.RuntimeException" ).init( "outer #token#", inner );
					try {
						throw( object=outer );
					}
					catch ( any e ) {
						writeLog( text="json cause #token#", log=name, type="error", exception=e );
					}
					var json = deserializeJSON( trim( fileRead( file ) ) );
					expect( json ).toHaveKey( "thrown" );
					expect( json.thrown ).toHaveKey( "name" );
					expect( json.thrown ).toHaveKey( "message" );
					// the existing stack trace key stays
					expect( json ).toHaveKey( "extendedStackTrace" );

					// walk the chain: every element has name and message, the next one is in "cause"
					var names = [];
					var messages = [];
					var node = json.thrown;
					while ( isStruct( node ) && names.len() < 20 ) {
						expect( node ).toHaveKey( "name" );
						expect( node ).toHaveKey( "message" );
						names.append( node.name );
						messages.append( node.message );
						node = structKeyExists( node, "cause" ) ? node.cause : "";
					}
					expect( names ).toInclude( "java.lang.IllegalStateException" );
					expect( messages ).toInclude( "inner #token#" );
					// the cause comes after the exception that has it
					expect( arrayFind( names, "java.lang.IllegalStateException" ) ).toBeGT( 1 );
				}
				finally {
					removeLog( name, file );
				}
			});

			it( title="xml layout writes xml events", body=function( currentSpec ) {
				var name = "log4jtest-xml-" & createUUID();
				var file = expandPath( "{lucee-config}/logs/#name#.log" );
				try {
					updateLog( name, "xml", "resource", { path: file } );
					var token = createUUID();
					writeLog( text="xml #token#", log=name, type="information" );
					var content = fileRead( file );
					expect( content ).toInclude( token );
					expect( content ).toInclude( "<" );
				}
				finally {
					removeLog( name, file );
				}
			});

			it( title="LDEV-6598 datadog layout uses the server config timezone, not the request timezone or the JVM default", body=function( currentSpec ) {
				var TimeZone = createObject( "java", "java.util.TimeZone" );
				expect( [ variables.configTZ, variables.requestTZ ] ).notToInclude( TimeZone.getDefault().getID() );
				var name = "log4jtest-datadog-" & createUUID();
				var file = expandPath( "{lucee-config}/logs/#name#.log" );
				var orig = getTimeZone();
				try {
					updateLog( name, "datadog", "resource", { path: file } );
					var token = createUUID();
					setTimeZone( variables.requestTZ );
					var before = fmt( "yyyy-MM-dd HH", variables.configTZ );
					try {
						throw( message="log4j #token#" );
					}
					catch ( any e ) {
						// the datadog layout needs an exception
						writeLog( text="log4j #token#", log=name, type="information", exception=e );
					}
					var after = fmt( "yyyy-MM-dd HH", variables.configTZ );
					var line = findLine( fileRead( file ), token );
					var ts = reMatch( "^\d{4}-\d{2}-\d{2} \d{2}", line );
					expect( ts.len() ).toBe( 1, line );
					expect( [ before, after ] ).toInclude( ts[ 1 ], "expected the config timezone [#variables.configTZ#: #before#], request timezone [#variables.requestTZ#: #fmt( "yyyy-MM-dd HH", variables.requestTZ )#], log line: #left( line, 150 )#" );
				}
				finally {
					setTimeZone( orig );
					removeLog( name, file );
				}
			});

			it( title="LDEV-6598 datasource appender, time column (request timezone set with setTimeZone)", skip=isH2NotSupported(), body=function( currentSpec ) {
				var id = "log4jtest_" & replace( lCase( createUUID() ), "-", "", "all" );
				var pw = request.ServerAdminPassword;
				var orig = getTimeZone();
				try {
					admin
						action="updateDatasource" type="server" password="#pw#"
						name="#id#" newName="#id#" classname="org.h2.Driver" bundlename="org.lucee.h2"
						dsn="jdbc:h2:#server._getTempDir( id )#/db;MODE=MySQL" dbusername="sa" dbpassword=""
						connectionLimit="10" connectionTimeout="0" blob="false" clob="false" validate="false" storage="false"
						allowed_select="true" allowed_insert="true" allowed_update="true" allowed_delete="true" allowed_alter="true"
						allowed_drop="true" allowed_revoke="true" allowed_create="true" allowed_grant="true";
					admin
						action="updateLogSettings" type="server" password="#pw#"
						name="#id#" level="info" appenderClass="datasource" appenderArgs={ "datasource": id, "table": id }
						layoutClass="classic" layoutArgs={};

					var token = createUUID();
					setTimeZone( variables.requestTZ );
					var before = fmt( "yyyy-MM-dd HH", variables.configTZ );
					writeLog( text="log4j #token#", log=id, type="information" );
					var after = fmt( "yyyy-MM-dd HH", variables.configTZ );
					// read the stored value as text, so reading it back does not convert it with any timezone
					var qry = queryExecute( "SELECT FORMATDATETIME(time, 'yyyy-MM-dd HH') AS t FROM #id# WHERE message LIKE ?", [ "%#token#%" ], { datasource: id } );
					expect( qry.recordcount ).toBe( 1 );
					expect( [ before, after ] ).toInclude( qry.t,
						"datasource: expected the config timezone [#variables.configTZ#: #before#], request timezone [#variables.requestTZ#: #fmt( "yyyy-MM-dd HH", variables.requestTZ )#], stored: #qry.t#" );
				}
				finally {
					setTimeZone( orig );
					try {
						admin action="removeLogSetting" type="server" password="#pw#" name="#id#";
					}
					catch ( any e ) {}
					try {
						admin action="removeDatasource" type="server" password="#pw#" name="#id#";
					}
					catch ( any e ) {}
				}
			});

		});
	}

	private void function updateLog( required string name, required string layout, required string appender, required struct args ) {
		admin action="updateLogSettings" type="server" password="#request.ServerAdminPassword#"
			name="#arguments.name#" level="info" appenderClass="#arguments.appender#" appenderArgs="#arguments.args#"
			layoutClass="#arguments.layout#" layoutArgs={};
	}

	private void function removeLog( required string name, required string file ) {
		try {
			admin action="removeLogSetting" type="server" password="#request.ServerAdminPassword#" name="#arguments.name#";
		}
		catch ( any e ) {}
		if ( fileExists( arguments.file ) ) fileDelete( arguments.file );
	}

	private boolean function isH2NotSupported() {
		try {
			return structIsEmpty( server.getTestService( "h2", server._getTempDir( "Log4jTest" ) ) );
		}
		catch ( any e ) {
			return true;
		}
	}

	private string function fmt( required string mask, required string tz ) {
		var sdf = createObject( "java", "java.text.SimpleDateFormat" ).init( arguments.mask );
		sdf.setTimeZone( createObject( "java", "java.util.TimeZone" ).getTimeZone( arguments.tz ) );
		return sdf.format( createObject( "java", "java.util.Date" ).init() );
	}

	private string function findLine( required string content, required string needle ) {
		var lines = listToArray( arguments.content, chr( 10 ) );
		for ( var line in lines ) {
			if ( find( arguments.needle, line ) ) return trim( line );
		}
		throw( message="no line with [#arguments.needle#] in: " & arguments.content );
	}
}
