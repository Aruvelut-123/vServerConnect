# GitHub Actions Workflows

This directory contains GitHub Actions workflows for automated building and releasing of vServerConnect.

## Workflows

### 1. Build and Test (`build.yml`)
**Triggers**: 
- Push to `main` or `develop` branches
- Pull requests to `main` branch

**Actions**:
- Sets up Java 17 environment
- Caches Gradle dependencies for faster builds
- Compiles and tests the plugin
- Uploads build artifacts

### 2. Manual Release (`release.yml`)
**Triggers**: Manual workflow dispatch

**Inputs**:
- `version`: Release version (format: x.y.z)
- `prerelease`: Whether this is a prerelease

**Actions**:
- Updates version in `gradle.properties`
- Creates git tag and commits
- Builds the plugin
- Generates changelog from git commits
- Creates GitHub release with changelog
- Uploads JAR file as release asset
- Pushes changes and tags

### 3. Automated Release (`automated-release.yml`)
**Triggers**: Push of tags matching `v*` pattern

**Actions**:
- Automatically creates a release when a version tag is pushed
- Generates changelog from commits
- Uploads JAR file as release asset
- Updates `latest` tag

## Usage

### Manual Release
1. Go to the "Actions" tab in your GitHub repository
2. Select "Create Release" workflow
3. Click "Run workflow"
4. Enter the version number (e.g., "1.2.0")
5. Set whether it's a prerelease
6. Click "Run workflow"

### Automated Release
1. Create and push a version tag:
   ```bash
   git tag v1.2.0
   git push origin v1.2.0
   ```
2. The workflow will automatically create a release

### Build Only
Simply push to any branch or create a pull request to trigger the build workflow.

## Permissions Required

The workflows require the following repository permissions:
- Actions: Read and write
- Contents: Read and write
- Metadata: Read

## Secrets

The workflows use the built-in `GITHUB_TOKEN` secret, which automatically has the necessary permissions. No additional secrets are required.

## Configuration

You can customize the workflows by editing the YAML files in this directory. Common customizations include:
- Java version
- Gradle arguments
- Release asset naming
- Changelog generation format

## Troubleshooting

### Build Failures
- Check that your Java version is compatible
- Ensure all dependencies are properly declared
- Verify that the Gradle wrapper is committed

### Release Failures
- Ensure the version format is correct (x.y.z)
- Check that you have push permissions to the repository
- Verify that the JAR file is built successfully

### Permission Issues
- Ensure the repository has the required permissions
- Check that the `GITHUB_TOKEN` is not restricted