package org.gradle.tools

/* Copyright 2007-2010 the original author or authors.
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

/**
 * This script obtains  the effective pom of the current project, reads its dependencies
 * and generates build.gradle scripts. It also generates settings.gradle for multimodule builds. <br/>
 *
 * It currently supports both single-module and multi-module POMs, inheritance, dependency management, properties - everything.
 *
 * @author Antony Stubbs <antony.stubbs@gmail.com>
 * @author Baruch Sadogursky <jbaruch@sadogursky.com>
 * */
class Maven2Gradle {

  def dependentWars = []
  def qualifiedNames
  def workingDir
  def effectiveSettings
  def effectivePom

  public static void main(String[] args) {
    new Maven2Gradle().convert(args)
  }

  def convert(String[] args) {
    workingDir = new File('.').canonicalFile
    println "Working path:" + workingDir.absolutePath + "\n"

    // use the Groovy XmlSlurper library to parse the text string
    effectivePom = new XmlSlurper().parseText(geEffectiveContents('pom', args))
    effectiveSettings = new XmlSlurper().parseText(geEffectiveContents('settings', args))
    String build
    def multimodule = effectivePom.name() == "projects"

    if (multimodule) {
      println "This is multi-module project.\n"
      def allProjects = effectivePom.project
      print "Generating settings.gradle... "
      qualifiedNames = generateSettings(workingDir.getName(), allProjects[0].artifactId, allProjects);
      println "Done."
      print "Configuring Dependencies... "
      def dependencies = [:];
      allProjects.each { project ->
        dependencies[project.artifactId.text()] = getDependencies(project, allProjects)
      }
      println "Done."


      def commonDeps = dependencies.get(allProjects[0].artifactId.text())
      build = """allprojects  {
  apply plugin: 'maven'

  ${getArtifactData(allProjects[0])}
}

subprojects {
  apply plugin: 'java'
  ${compilerSettings(allProjects[0])}
  ${packageSources(allProjects[0])}
  ${getRepositoriesForProjects(allProjects)}
  ${globalExclusions(allProjects[0])}
  ${commonDeps}
  ${testNg(commonDeps)}
  }
"""
      modules(allProjects, false).each { module ->
        def id = module.artifactId.text()
        String moduleDependencies = dependencies.get(id)
        boolean warPack = module.packaging.text().equals("war")
        def hasDependencies = !(moduleDependencies == null || moduleDependencies.isEmpty())
        print "Generating build.gradle for module ${id}... "
        File submoduleBuildFile = new File(projectDir(module), 'build.gradle')
        def group = ''
        if (module.groupId != allProjects[0].groupId) {
          group = "group = '${module.groupId}'"
        }
        String moduleBuild = """
${group}
description = '${module.name}'

"""
        if (warPack) {
          moduleBuild += """apply plugin: 'war'
"""
          if (dependentWars.any {project ->
            project.groupId.text() == module.groupId.text() &&
                    project.artifactId.text() == id
          }) {
            moduleBuild += """jar.enabled = true
"""
          }
        }
        if (hasDependencies) {
          moduleBuild += moduleDependencies
        }

        moduleBuild += testNg(moduleDependencies)

        if (submoduleBuildFile.exists()) {
          print "(backing up existing one)... "
          submoduleBuildFile.renameTo(new File("build.gradle.bak"))
        }
        def packageTests = packageTests(module);
        if (packageTests) {
          moduleBuild += packageTests;
        }
        submoduleBuildFile.text = moduleBuild
        println "Done."
      }
      //TODO deployment
      def uploadArchives = {
        """


uploadArchives {
  group = 'Maven'
  description = "Does a maven deploy of archives artifacts."

  repositories.mavenDeployer {
        name = 'sshDeployer' // optional
        repository(url: "http://repos.mycompany.com/releases") {
            authentication(userName: "me", password: "myPassword")
        }
      configurePom(pom)
    }
}


"""
      }
    } else {//simple
      println "This is single module project."
      build = """apply plugin: 'java'
apply plugin: 'maven'

${getArtifactData(effectivePom)}

description = \"""${effectivePom.name}\"""

${compilerSettings(effectivePom)}
${globalExclusions(effectivePom)}

"""

      print "Configuring Maven repositories... "
      Set<String> repoSet = new LinkedHashSet<String>();
      getRepositoriesForModule(effectivePom, repoSet)
      String repos = """repositories {
        $localRepoUri
"""
      repoSet.each {
        repos = "${repos} ${it}\n"
      }
      build += "${repos}}\n"
      println "Done."
      print "Configuring Dependencies... "
      String dependencies = getDependencies(effectivePom, null)
      build += dependencies
      println "Done."

      String packageTests = packageTests(effectivePom);
      if (packageTests) {
        build += '//packaging tests'
        build += packageTests;
      }
      print "Generating settings.gradle if needed... "
      generateSettings(workingDir.getName(), effectivePom.artifactId, null);
      println "Done."

    }
    print "Generating main build.gradle... "
    def buildFile = new File("build.gradle")
    if (buildFile.exists()) {
      print "(backing up existing one)... "
      buildFile.renameTo(new File("build.gradle.bak"))
    }
    buildFile.text = build
    println "Done."
  }

  def globalExclusions = {project ->
    def exclusions = ''
    def enforcerPlugin = plugin('maven-enforcer-plugin', project)
    def enforceGoal = pluginGoal('enforce', enforcerPlugin)
    if (enforceGoal) {
      exclusions += 'configurations.allObjects {\n'
      enforceGoal.configuration.rules.bannedDependencies.excludes.childNodes().each {
        def tokens = it.text().tokenize(':')
        exclusions += "it.exclude group: '${tokens[0]}'"
        if (tokens.size() > 1 && tokens[1] != '*') {
          exclusions += ", module: '${tokens[1]}'"
        }
        exclusions += '\n'
      }
    }
    exclusions = exclusions ? exclusions += '}' : exclusions
    exclusions
  }

  def testNg = {moduleDependencies ->
    if (moduleDependencies.contains('testng')) {
      """test.useTestNG()
"""
    } else {
      ''
    }
  }

  def modules = {allProjects, incReactors ->
    return allProjects.findAll { project ->
      !project.parent.text().empty && (incReactors || project.packaging.text() != 'pom')
    }
  }

  def fqn = {project, allProjects ->
    def buffer = new StringBuilder()
    generateFqn(project, allProjects, buffer)
    return buffer.toString()
  }

  private generateFqn(def project, def allProjects, StringBuilder buffer) {
    def artifactId = project.artifactId.text()
    buffer.insert(0, ":${artifactId}")
    //we don't need the top-level parent in gradle, so we stop on it
    if (project.parent.artifactId.text() != allProjects[0].artifactId.text()) {
      generateFqn(allProjects.find {fullProject ->
        fullProject.artifactId.text() == project.parent.artifactId.text()
      }, allProjects, buffer)
    }
  }


  def localRepoUri = {
    //we have local maven repo full with good stuff. Let's reuse it!
    String userHome = System.properties['user.home']
    userHome = userHome.replaceAll('\\\\', '/')
    def localRepoUri = new File(effectiveSettings.localRepository.text()).toURI().toString()
    if (localRepoUri.contains(userHome)) {
      localRepoUri = localRepoUri.replace(userHome, '${System.properties[\'user.home\']}')
    }
    //in URI format there is one slash after file, while  Gradle needs two
    localRepoUri = localRepoUri.replace('file:/', 'file://')
    """mavenRepo url: \"${localRepoUri}\"
    """
  }

  private String getArtifactData(project) {
    return """  group = '$project.groupId'
  version = '$project.version'
  """;
  }

  private String getRepositoriesForProjects(projects) {
    print 'Configuring Repositories... '
    String repos = """  repositories {
    ${localRepoUri()}
"""
    def repoSet = new LinkedHashSet<String>();
    projects.each {
      getRepositoriesForModule(it, repoSet)
    }
    repoSet.each {
      repos = "${repos} ${it}\n"
    }
    repos = "${repos}}\n"
    println "Done."
    return repos
  }

  private void getRepositoriesForModule(module, repoSet) {
    module.repositories.repository.each {
      repoSet.add("""   mavenRepo urls: [\"${it.url}\"]""")
    }
    //No need to include plugin repos - who cares about maven plugins?
  }

  private String getDependencies(project, allProjects) {
// use GPath to navigate the object hierarchy and retrieve the collection of dependency nodes.
    def dependencies = project.dependencies.dependency
    def war = project.packaging == "war"

    def compileTimeScope = []
    def runTimeScope = []
    def testScope = []
    def providedScope = []
    def systemScope = []

    //cleanup duplicates from parent

// using Groovy Looping and mapping a Groovy Closure to each element, we collect together all
// the dependency nodes into corresponding collections depending on their scope value.
    dependencies.each() {
      if (!duplicateDependency(it, project, allProjects)) {
        def scope = (elementHasText(it.scope)) ? it.scope : "compile"
        switch (scope) {
          case "compile":
            compileTimeScope.add(it)
            break
          case "test":
            testScope.add(it)
            break
          case "provided":
            providedScope.add(it)
            break
          case "runtime":
            runTimeScope.add(it)
            break
          case "system":
            systemScope.add(it)
            break
        }
      }
    }

    /**
     * print function then checks the exclusions node to see if it exists, if
     * so it branches off, otherwise we call our simple print function
     */
    def createGradleDep = {String scope, StringBuilder sb, mavenDependency ->
      def projectDep = allProjects.find { prj ->
        return prj.artifactId.text() == mavenDependency.artifactId.text() && prj.groupId.text() == mavenDependency.groupId.text()
      }
      if (projectDep) {
        createProjectDependency(projectDep, sb, scope, allProjects)
      } else {
        def dependencyProperties = null;
        if (!war && scope == 'providedCompile') {
          scope = 'compile'
          dependencyProperties = [provided: true]
        }
        def exclusions = mavenDependency.exclusions.exclusion
        if (exclusions.size() > 0 || dependencyProperties) {
          createComplexDependency(mavenDependency, sb, scope, dependencyProperties)
        } else {
          createBasicDependency(mavenDependency, sb, scope)
        }
      }
    }


    StringBuilder build = new StringBuilder()
    if (!compileTimeScope.isEmpty() || !runTimeScope.isEmpty() || !testScope.isEmpty() || !providedScope.isEmpty() || !systemScope.isEmpty()) {
      build.append("dependencies {").append("\n")
// for each collection, one at a time, we take each element and call our print function
      if (!compileTimeScope.isEmpty()) compileTimeScope.each() { createGradleDep("compile", build, it) }
      if (!runTimeScope.isEmpty()) runTimeScope.each() { createGradleDep("runtime", build, it) }
      if (!testScope.isEmpty()) testScope.each() { createGradleDep("testCompile", build, it) }
      if (!providedScope.isEmpty()) providedScope.each() { createGradleDep("providedCompile", build, it) }
      if (!systemScope.isEmpty()) systemScope.each() { createGradleDep("system", build, it) }
      build.append("}\n")
    }
    return build.toString();
  }

  def compilerSettings = {project ->
    def configuration = plugin('maven-compiler-plugin', project).configuration
    return "sourceCompatibility = ${configuration.source.text() ?: '1.5'}\ntargetCompatibility = ${configuration.target.text() ?: '1.5'}\n"
  }

  def plugin = {artifactId, project ->
    project.build.plugins.plugin.find {pluginTag ->
      pluginTag.artifactId.text() == artifactId
    }
  }

  def pluginGoal = { goalName, plugin ->
    plugin.executions.execution.find { exec ->
      exec.goals.goal.find {gl ->
        gl.text().startsWith(goalName)
      }
    }
  }

  def packSources = {sourceSets ->
    def sourceSetStr = ''
    if (!sourceSets.empty) {
      sourceSetStr = """task packageSources(type: Jar) {
classifier = 'sources'
"""
      sourceSets.each { sourceSet ->
        sourceSetStr += """from sourceSets.${sourceSet}.allSource
"""
      }
      sourceSetStr += """
}
artifacts.archives packageSources"""
    }
    println 'Done.'
    sourceSetStr
  }


  def packageTests = {project ->
    print 'Adding tests packaging...'
    def jarPlugin = plugin('maven-jar-plugin', project)
    pluginGoal('test-jar', jarPlugin) ? """
task packageTests(type: Jar) {
  from sourceSets.test.classes
  classifier = 'tests'
}
artifacts.archives packageTests
""" : ''
  }

  def packageSources = { project ->
    def sourcePlugin = plugin('maven-source-plugin', project)
    def sourceSets = []
    if (sourcePlugin) {
      println 'Adding sources packaging...'
      if (pluginGoal('jar', sourcePlugin)) {
        sourceSets += 'main'
      } else if (pluginGoal('test-jar', sourcePlugin)) {
        sourceSets += 'test'
      }
    }
    packSources(sourceSets)
  }

  private boolean duplicateDependency(dependency, project, allProjects) {
    def parentTag = project.parent
    if (allProjects == null || parentTag.isEmpty()) {//simple project or no parent
      return false;
    } else {
      def parent = allProjects.find {
        it.groupId.equals(parentTag.groupId) && it.artifactId.equals(parentTag.artifactId)
      }
      def duplicate = parent.dependencies.dependency.any {
        it.groupId.equals(dependency.groupId) && it.artifactId.equals(dependency.artifactId)
      }
      if (duplicate) {
        return true;
      } else {
        duplicateDependency(dependency, parent, allProjects)
      }
    }
  }

  def artifactId = {File dir ->
    return new XmlSlurper().parse(new File(dir, 'pom.xml')).artifactId.text()
  }

  def projectDir = {project ->
    return new File(project.build.directory.text()).parentFile
  }

  private def generateSettings(def dirName, def mvnProjectName, def projects) {
    def qualifiedNames = [:]
    def projectName = "";
    if (dirName != mvnProjectName) {
      projectName = """rootProject.name = '${mvnProjectName}'
"""
    }
    def modulePoms = modules(projects, true)

    def modules = new StringBuilder();
    def artifactIdToDir = [:]
    if (projects) {
      modulePoms.each { project ->
        def fqn = fqn(project, projects)
        artifactIdToDir[fqn] = workingDir.toURI().relativize(projectDir(project).toURI()).path
        modules.append("'${fqn}', ")
      }
      def strLength = modules.length()
      if (strLength > 2) {
        modules.delete(strLength - 2, strLength)
      }
    }
    File settingsFile = new File("settings.gradle")
    if (settingsFile.exists()) {
      print "(backing up existing one)... "
      settingsFile.renameTo(new File("settings.gradle.bak"))
    }
    def settingsText = "${projectName} ${modules.length() > 0 ? "include ${modules.toString()}" : ''}"
    artifactIdToDir.each {entry ->
      settingsText += """
project('$entry.key').projectDir = """ + '"$rootDir/' + "${entry.value}" + '" as File'
    }
    settingsFile.text = settingsText
    return qualifiedNames
  }

  String geEffectiveContents(String file, String[] args) {
//TODO work on output stream, without writing to file
    def fileName = "effective-${file}.xml"
    print "Wait, obtaining effective $file... "

    def ant = new AntBuilder()   // create an antbuilder
    ant.exec(outputproperty: "cmdOut",
            errorproperty: "cmdErr",
            failonerror: "true",
            executable: ((String) System.properties['os.name']).toLowerCase().contains("win") ? "mvn.bat" : "mvn") {
      arg(line: """-Doutput=${fileName} org.apache.maven.plugins:maven-help-plugin:2.2-SNAPSHOT:effective-$file""")
      env(key: "JAVA_HOME", value: System.getProperty("java.home"))
    }

//print the output if verbose flag is on
    println((args.any {it.equals("-verbose")}) ? "\n ${ant.project.properties.cmdOut}" : "Done.")

// read in the effective pom file
    File tmpFile = new File(fileName);
    if (!args.any {it.equals("-keepFile")}) {
      tmpFile.deleteOnExit()
    }
    // get it's text into a string
    return tmpFile.text
  }

/**
 * complex print statement does one extra task which is
 * iterate over each <exclusion> node and print out the artifact id.
 * It also tackles the properties attached to dependencies
 */
  private def createComplexDependency(it, build, scope, Map dependencyProperties) {
    build.append("${scope}(${contructSignature(it)}) {\n")
    it.exclusions.exclusion.each() {
      build.append("exclude(module: '${it.artifactId}')\n")
    }
    if (dependencyProperties) {
      dependencyProperties.keySet().each { key ->
        build.append("$key : ${dependencyProperties.get(key)}\n")
      }
    }
    build.append("}\n")
  }

/**
 * Print out the basic form og gradle dependency
 */
  private def createBasicDependency(mavenDependency, build, String scope) {
    def classifier = contructSignature(mavenDependency)
    build.append("${scope} ${classifier}\n")
  }
/**
 * Print out the basic form of gradle dependency
 */
  private def createProjectDependency(projectDep, build, String scope, allProjects) {
    if (projectDep.packaging.text() == 'war') {
      dependentWars += projectDep
    }
    build.append("${scope} project('${fqn(projectDep, allProjects)}')\n")
  }

/**
 * Construct and return the signature of a dependency, including it's version and
 * classifier if it exists
 */
  private def contructSignature(mavenDependency) {
    def gradelDep = "group: '${mavenDependency.groupId.text()}', name: '${mavenDependency.artifactId.text()}', version:'${mavenDependency?.version?.text()}'"
    def classifier = elementHasText(mavenDependency.classifier) ? gradelDep + ", classifier:'" + mavenDependency.classifier.text().trim() + "'": gradelDep
    return classifier
  }

/**
 * Check to see if the selected node has content
 */
  private boolean elementHasText(it) {
    return it.text().length() != 0
  }
}