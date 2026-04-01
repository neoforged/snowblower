# ☃️ Snowblower

**Snowblower** is a command-line utility used to create a Git repository of decompiled Minecraft code (named a **Snowman repo**). It has three primary use cases:

1. To inspect the entire set of changes between Minecraft versions, as each version gets an individual commit in the history.
2. To determine which version introduced a specific piece of code, using git blame.
3. To load the generated output into an IDE to get syntax highlighting and code indexing, especially for bleeding-edge versions of Minecraft. Note that this code will NOT recompile successfully, as decompilation isn't perfect. For recompilation, see [the NeoForm project](https://github.com/neoforged/NeoForm/).

## Usage

This section covers the basic command-line usage for generating a repository of decompiled Minecraft code. Most users can simply run the tool locally as described below.

You can download the latest and previous versions of Snowblower directly from its [project page](https://projects.neoforged.net/neoforged/snowblower). **Java 21 or higher** is required to run Snowblower.

### Basic usage

The most common use case is to generate a comprehensive repository of all versions of a specific type, usually either releases only or all releases and snapshots. The following command processes every release version starting from `1.14.4`, which is the first version where official Mojang mappings are available.

Example (not recommended; see [below section](#excluding-unnecessary-files)):
```sh
java -jar snowblower-2.0.31-all.jar --output ./output --branch release --start-over-if-required
```

- `--output ./output`: Specifies the output directory for the generated repository, can be a path relative to the current working directory or an absolute path.
- `--branch release`: Designates the target branch, which is typically `release` or `dev`.
  - Using the `dev` branch will also include snapshot versions in the generated output.
- `--start-over-if-required`: Ensures the process starts from scratch if necessary (e.g., when updating Snowblower or changing the start or target versions).

💡 **Note**: Generating every version takes a considerable amount of time and a good amount of CPU and RAM. Ensure that you allocate at least 2-3 GBs of RAM. A higher CPU core count also typically helps speed things up, as decompilation (the main bottleneck) will utilize every available core when possible.

### Excluding unnecessary files

To prevent repository clutter and large diffs, especially with structures (`.nbt`) and images (`.png`), it is recommended to exclude them. NBT files frequently change due to being regenerated with an internal version field, causing unnecessary noise in the commit history.

Example:
```sh
java -jar snowblower-2.0.31-all.jar --output ./output --branch release --exclude "**.nbt" --exclude "**.png" --start-over-if-required
```

### Processing specific versions

If you need to generate a repository for a limited range of versions, you can use the `--start-ver` and `--target-ver` flags. This will process everything from the specified start version up to and including the target version.

Example for all release versions between 1.20.1 and 1.21 (inclusive):
```sh
java -jar snowblower-2.0.31-all.jar --output ./output --branch release --start-ver 1.20.1 --target-ver 1.21 --start-over-if-required
```

### Integrating with remote Git repositories

Snowblower supports checking out from and pushing to remote Git repositories, useful for viewing the generated output from anywhere and for stateless CI environments. ⚠️ **WARNING** ⚠️: Care must be taken to ensure any remote Git repositories are private and authenticated, as Minecraft's code is copyrighted by Mojang and not to be freely distributed.

* To set up a remote origin, use the `--remote <url>` flag. In CI environments when using GitHub, you may need to set up a [personal access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#using-a-personal-access-token-on-the-command-line) for proper authentication, so your final URL will look something like: `https://<USERNAME>:<TOKEN>@https://github.com/<REPO_USER>/<REPO>.git`
* To check out the selected branch from the remote repo (if it exists), add the `--checkout` flag. This allows resuming from the last committed version, e.g., in CI tasks executed on every Minecraft version release.
* To push the generated result when done, add the `--push` flag. Note that this option always performs a **force push**, so treat it with care.

Example that resumes from remote and pushes the generated result back to the remote:
```sh
java -jar snowblower-2.0.31-all.jar --output ./output --branch release --exclude "**.nbt" --exclude "**.png" \
--start-over-if-required --remote https://<USERNAME>:<TOKEN>@https://github.com/<REPO_USER>/<REPO>.git \
--checkout --push
```

### April Fools' Day versions

Snowblower also supports generating branches for April Fools' Day versions, separate from the mainline releases. Snowblower includes default support for `20w14infinite`, `22w13oneblockatatime`, `23w13a_or_b`, `24w14potato`, `25w14craftmine`, and `26w14a` under the branch name `april-fools/<version>`. These branches will generate exactly two versions: the base version that the given April Fools' Day version is believed to have been forked from, and the April Fools' Day version itself.

To customize your own branches (for later April Fools' Day versions or otherwise), consult [the default branch config](src/main/resources/default_branch_config.json) as a guide. You can pass your own branch configs using the `--cfg <uri>` flag, either passing it by `file://` or `https://`.

Example for `25w14craftmine` (generates `1.21.5-rc1` then `25w14craftmine`):
```sh
java -jar snowblower-2.0.31-all.jar --output ./output --branch april-fools/25w14craftmine --exclude "**.nbt" --exclude "**.png" --start-over-if-required
```

### Exploring more flags

Snowblower has more flags and options for advanced use cases. You can see a complete list with descriptions by running the Snowblower JAR without any arguments.

Example:
```sh
java -jar Snowblower.jar
```

## Why the names "Snowblower" and "Snowman"?

The origin of the names is a nod to a quirk of older Minecraft versions. A quick history lesson:

* Beginning in 1.8.2-pre5, Mojang stopped stripping the Local Variable Table (or LVT) from JVM class files in released JARs of Minecraft.
This table contains information about local variables defined inside a method, including their name, type, and index on the stack.
This information is entirely unnecessary for running the game but is useful for modding tools, like decompilers. (The change was almost assuredly made specifically to aid modders.)
* However, likely due to legal reasons, Mojang did not preserve the original local variable names in the released JARs (this was during the time when Minecraft's code was obfuscated).
Instead, all local variables in every LVT had their name set to `☃` (`U+2603`), the Unicode codepoint for the snowman emoji.
* This practice was maintained for *over 6 years* until 21w19a, when the identifier was changed to `â˜ƒ` for all local variables instead, thus marking the end of the snowman era.
* In 21w37a, LVT identifiers were changed to instead be of the form `$$<index>`, counting up from `0` for every local variable defined in the table.
* Finally, the practice of renaming local variables was discontinued beginning with 26.1-snapshot-1, when Mojang turned off obfuscation of Java Edition for good.
