import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.maven.publish)
}

group = "io.github.pedronveloso"

version = "0.11.0"

kotlin { explicitApi() }

android {
  namespace = "com.pedronveloso.logviewer"
  compileSdk = 37
  defaultConfig {
    minSdk = 26
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
  lint { warningsAsErrors = true }
  testOptions {
    unitTests.isIncludeAndroidResources = true
    unitTests.all {
      it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
      it.jvmArgs("-XX:TieredStopAtLevel=1")
    }
  }
}

mavenPublishing {
  configure(
    AndroidSingleVariantLibrary(
      variant = "release",
      sourcesJar = SourcesJar.Sources(),
      javadocJar = JavadocJar.Empty(),
    ),
  )
  publishToMavenCentral()
  signAllPublications()

  coordinates(group.toString(), "logviewer", version.toString())
  pom {
    name.set("In-app log viewer")
    description.set("A Compose viewer for Timber logs in Android apps")
    url.set("https://github.com/pedronveloso/pnv-in-app-logs-viewer")
    licenses {
      license {
        name.set("The Apache License, Version 2.0")
        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        distribution.set("repo")
      }
    }
    developers {
      developer {
        id.set("pedronveloso")
        name.set("Pedro Veloso")
        url.set("https://github.com/pedronveloso")
      }
    }
    scm {
      url.set("https://github.com/pedronveloso/pnv-in-app-logs-viewer")
      connection.set("scm:git:https://github.com/pedronveloso/pnv-in-app-logs-viewer.git")
      developerConnection.set(
        "scm:git:ssh://git@github.com/pedronveloso/pnv-in-app-logs-viewer.git",
      )
    }
  }
}

dependencies {
  lintChecks(libs.composeLints)
  api(libs.timber)
  implementation(libs.coreKtx)
  api(libs.coroutinesAndroid)
  api(platform(libs.composeBom))
  api(libs.composeUi)
  compileOnly(libs.composeUiToolingPreview)
  implementation(libs.composeMaterial3)
  implementation(libs.composeIcons)
  implementation(libs.activityCompose)
  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidxTestCore)
  testImplementation(libs.coroutinesTest)
  testImplementation(platform(libs.composeBom))
  testImplementation(libs.composeUiTest)
  testImplementation(libs.composeUiTestManifest)
  androidTestImplementation(platform(libs.composeBom))
  androidTestImplementation(libs.composeUiTest)
  androidTestImplementation(libs.androidxJunit)
  androidTestImplementation(libs.androidxRunner)
  androidTestImplementation(libs.espressoCore)
  debugImplementation(libs.composeUiTestManifest)
  debugImplementation(libs.composeUiTooling)
}
