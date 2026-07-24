# Publishing `com.adfinia:sdk-android` to Maven Central

This module publishes to the **Sonatype Central Portal** (central.sonatype.com)
using the [`com.vanniktech.maven.publish`](https://vanniktech.github.io/gradle-maven-publish-plugin/)
Gradle plugin. The plugin handles the Portal upload, POM generation, GPG
signing, and the sources + Dokka-javadoc jars that Central requires.

Coordinates: **`com.adfinia:sdk-android:1.2.0`** (set in `sdk/build.gradle.kts`).

## Prerequisite: verify the `com.adfinia` namespace (one-time)

Before the first publish, the **`com.adfinia` namespace must be verified** in
the Central Portal:

1. Sign in at https://central.sonatype.com with the Adfinia account.
2. Add the namespace `com.adfinia` and complete domain/DNS verification
   (a TXT record on `adfinia.com`, or the GitHub-org verification path).
3. Wait until the namespace shows **Verified**. Uploads to an unverified
   namespace are rejected.

This is done once; it does not need repeating for each release.

## The four secrets

The plugin reads these standard Gradle property names (nothing is hardcoded in
the repo). Provide all four at publish time:

| Property | What it is |
|----------|------------|
| `mavenCentralUsername` | Central Portal **user token** name (Portal -> Account -> Generate User Token). NOT your portal login. |
| `mavenCentralPassword` | Central Portal user token **password**. |
| `signingInMemoryKey` | Your **GPG secret key**, ASCII-armored. See "Preparing the GPG key" below. |
| `signingInMemoryKeyPassword` | Passphrase for that GPG key. |

### How to supply them (pick one)

- **`~/.gradle/gradle.properties`** (recommended for a local founder machine;
  never commit it):

  ```properties
  mavenCentralUsername=<portal-token-name>
  mavenCentralPassword=<portal-token-password>
  signingInMemoryKey=<ascii-armored-gpg-secret-key>
  signingInMemoryKeyPassword=<gpg-passphrase>
  ```

- **Environment variables** (each Gradle property `foo` maps to
  `ORG_GRADLE_PROJECT_foo`):

  ```bash
  export ORG_GRADLE_PROJECT_mavenCentralUsername='<portal-token-name>'
  export ORG_GRADLE_PROJECT_mavenCentralPassword='<portal-token-password>'
  export ORG_GRADLE_PROJECT_signingInMemoryKey="$(cat adfinia-signing-key.asc)"
  export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword='<gpg-passphrase>'
  ```

- **`-P` flags** on the command line (fine for a one-off; visible in shell
  history / process list, so prefer the two options above for real runs):

  ```bash
  ./gradlew publishAndReleaseToMavenCentral --no-configuration-cache \
    -PmavenCentralUsername='<portal-token-name>' \
    -PmavenCentralPassword='<portal-token-password>' \
    -PsigningInMemoryKey="$(cat adfinia-signing-key.asc)" \
    -PsigningInMemoryKeyPassword='<gpg-passphrase>'
  ```

The CI workflow (`.github/workflows/publish.yml`) wires the same four values
from GitHub Actions secrets (`OSSRH_USERNAME`, `OSSRH_PASSWORD`, `SIGNING_KEY`,
`SIGNING_PASSWORD`) into these `ORG_GRADLE_PROJECT_*` env vars on a
`sdk-android-v*` tag push.

### Preparing the GPG key (`signingInMemoryKey`)

`signingInMemoryKey` is the **ASCII-armored secret key** (the plugin accepts it
with or without the `-----BEGIN/END-----` header lines). Produce it with:

```bash
# Export the secret key (use the key id / email you created for signing)
gpg --armor --export-secret-keys <KEY_ID> > adfinia-signing-key.asc
# Publish the corresponding PUBLIC key to a keyserver so Central can verify it:
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

Then set `signingInMemoryKey` to the contents of `adfinia-signing-key.asc` and
`signingInMemoryKeyPassword` to that key's passphrase.

## The one command

With the namespace verified and the four secrets in place:

```bash
./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
```

That builds the release AAR + sources jar + Dokka javadoc jar, signs every
artifact, uploads the bundle to the Central Portal, and **auto-releases** it to
Maven Central. `--no-configuration-cache` is required (the publish tasks are not
configuration-cache compatible).

### Dry run / staging only (does not release)

To upload to a Portal staging deployment and inspect it before releasing:

```bash
./gradlew publishToMavenCentral --no-configuration-cache
```

Then review and release manually from the Central Portal UI.

## Notes

- Version lives in exactly one place: `coordinates("com.adfinia", "sdk-android",
  "1.2.0")` in `sdk/build.gradle.kts`. Bump it there; the CI tag guard checks a
  `sdk-android-vX.Y.Z` tag against it.
- Maven Central is **immutable**: a version can be published once. Bump the
  version for any re-publish.
- The target host (Central Portal) is set authoritatively by
  `publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)` in the build script. Do
  not also set a `SONATYPE_HOST` Gradle property; declaring the host twice fails
  configuration ("value is final and cannot be changed").
