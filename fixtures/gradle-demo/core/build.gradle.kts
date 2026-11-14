plugins { `java-library` }
dependencies {
    api(libs.guava)
    implementation(libs.commons.lang3)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
}
