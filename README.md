# ☃️ Snowblower

A utility to create a git repository of Minecraft decompiled code to make it easier to see what changed between minecraft versions.

## How to use

This section covers the basic command-line usage for generating a repository of decompiled Minecraft code. Most users can simply run the tool locally as described below.

You can download the latest and previous versions of Snowblower directly from its [project page](https://projects.neoforged.net/neoforged/snowblower).

### Basic usage

The most common use case is to generate a comprehensive repository of all supported versions. The following command processes every version starting from `1.14.4`, which is the first version where official Mojang mappings are available.

```sh
java -jar Snowblower.jar --output $OUTPUT --branch $BRANCH --start-over-if-required
```

- `--output $OUTPUT`: Specifies the output directory for the generated repository.
  - This can be specified either as an absolute path or some path releative to the `jar` file.
  - `output/$BRANCH` or `output/$VERSION` is recommended to keep your generated repositories organized. (`$VERSION` being a specific Minecraft version)
- `--branch $BRANCH`: Designates the target branch, which can be `release` or `dev`.
  - Using `dev` will include snapshots in the generated output.
- `--start-over-if-required`: Ensures the process starts from scratch if necessary.

**Note**: Generating every version takes a considerable amount of time.

### Excluding unnecessary files

To prevent repository clutter and large diffs, especially with structures (`.nbt`) and images (`.png`), it is recommended to exclude them. These files frequently change due to internal version values, causing noise in the commit history.

```sh
java -jar Snowblower.jar --output $OUTPUT --branch $BRANCH --exclude "**.nbt" --exclude "**.png" --start-over-if-required
```

### Processing specific versions

If you need to generate a repository for a limited range of versions, you can use the `--start-ver` and `--target-ver` flags. This will process everything from the specified start version up to and including the target version.

```sh
java -jar Snowblower.jar --output $OUTPUT --branch $BRANCH --start-ver 1.20.1 --target-ver 1.21 --start-over-if-required
```

### Exploring more flags

Snowblower has many more flags and options for advanced users. You can see a complete list by running the jar file without any arguments.

```sh
java -jar Snowblower.jar
```
