# Fabric Loom - Sin² Edition
A fork of [Fabric's Gradle plugin](https://github.com/FabricMC/fabric-loom/tree/dev/0.2) to make it do things asie didn't want it to do.

Usage: `gradlew genSources eclipse/idea/vscode`
(Use `./gradle` on macOS and Linux)


## What's new?
* [FernFlower](https://github.com/FabricMC/intellij-fernflower) switched to [ForgeFlower](https://github.com/MinecraftForge/ForgeFlower) for `genSources`
* Support for Enigma mappings (and parameter names as a result)
* Support for gz compressed Tiny mappings
* Support to pull Enigma mappings straight from Github
* Access Transformers
* Easier additional remapped jar tasks
* Optional non-forking decompiling for `genSources`
* Guaranteed Gradle 4.9 support


## What do I need to change?
Whilst not a whole lot needs to change compared to a normal Loom setup, there is a pair of tweaks that have to be made in order to get said setup running. A full example of a working `build.gradle` using everything Sin² offers can be found [here](https://github.com/Chocohead/Fabric-ASM/blob/master/build.gradle).

### Repos
First, both the Forge and Jitpack mavens are needed to grab ForgeFlower and a [Tiny Remapper fork](https://github.com/Chocohead/tiny-remapper) respectively in order for Sin² to work:
```groovy
maven {
	name = "Forge"
	url = "https://files.minecraftforge.net/maven/"
}
maven { 
	name = "Jitpack"
	url = "https://jitpack.io/"
}
```
If using a Gradle setup similar to the [Fabric Example Mod](https://github.com/FabricMC/fabric-example-mod), these will want to be added to the `pluginManagement` `repositories` block in `settings.gradle`.

If using a more stockish Gradle setup, these will want to be added to the `buildscript` `repositories` block in `build.gradle` instead.

### Loom version
Second, the Gradle plugin needs to change in order to pull the right version of Loom. Sin² versions are marked by the short Git commit revision. The following will need to be switched in `build.gradle`:
```groovy
plugins {
	//Old/normal Loom plugin
	//id 'fabric-loom' version '0.2.5-SNAPSHOT'
	//Sin² Edition Loom
	id 'fabric-loom' version '5784f06'
	...
}
```
When using using a Gradle setup similar to the Fabric Example Mod.
```groovy
buildscript {
	repositories {
		...
	}
	dependencies {
		//Old/normal Loom plugin
		//classpath 'net.fabricmc:fabric-loom:0.2.5-SNAPSHOT'
		//Sin² Edition Loom
		classpath 'com.github.Chocohead:fabric-loom:5784f06'
	}
}
```
When using a more stockish Gradle setup.

#### Which branch do I use?
Each branch is based on an upstream version of Loom (see table below); the most recent commit a branch has is likeliest the best one to use. When swapping between Loom forks, aiming to match like for like versions minimises how much has to change in your `build.gradle` in one go (and thus how much can go wrong). Features are not always backported however so it might prove prudent to update forwards if a feature you need is missing. Any problems or backport requests can be made [here](https://github.com/Chocohead/fabric-loom/issues).

Stock Version | Sin² Branch | Example Sin² Version
:---: | :---: | :---:
0.1.0 | [sin](https://github.com/Chocohead/fabric-loom/tree/sin) | **3c39479**
0.1.1 | *\<None\>* | -
0.2.0 | [*\<Floating\>*](https://github.com/Chocohead/fabric-loom/compare/3c39479...f7f4a45) | **2665770** to **f7f4a45**
0.2.1 | [ATs](https://github.com/Chocohead/fabric-loom/tree/ATs) | **89a5973**
0.2.2 | [sin²](https://github.com/Chocohead/fabric-loom/tree/sin²) | **51f7373**
0.2.3 | [*\<Floating\>*](https://github.com/Chocohead/fabric-loom/compare/f2fc524...32e0cc5) | **c4551b3** and **32e0cc5**
0.2.4 | [openfine](https://github.com/Chocohead/fabric-loom/tree/openfine) | **7eb4201**
0.2.5 | [dust](https://github.com/Chocohead/fabric-loom/tree/dust) | **5784f06**


## How do I use the new things?
Once you've added the two maven repositories to your Gradle setup, ForgeFlower decompiling will be used for `genSources`. For the other additional features however, more changes are needed:

### Running with Enigma mappings
Normal Tiny files don't ship with parameter or local variable names, whilst Enigma mappings do. Thus in order to get parameter mappings for methods, Enigma mappings have to be used instead. This causes additional excitment as the Enigma mappings don't come with [Intermediary mappings](https://github.com/FabricMC/intermediary). Fortunately this is all be handled in the background and the additional Intermediaries will be downloaded if needed for the version of Minecraft being used. Several more steps will be noticed in the build process as a result as the two mapping sets then need to be merged and rewritten to the expected Tiny format used later by Loom. This only needs to happen once every time the mappings are changed though, so it's not so bad.

If previously the mappings dependency looked like
```groovy
mappings "net.fabricmc:yarn:19w13a.2"
```
In order to use the compressed form, it would need to be changed to
```groovy
mappings "net.fabricmc:yarn:19w13a.2:enigma@zip"
```
Nothing else is required, when the project is next evaluated the change will be detected by the lack of a method parameters file and thus the mappings rebuilt. In theory at least, it's normally quite good at behaving.  


### Running with gz compressed Tiny mappings
Whilst not making that much of a difference in the grand scheme of things, using the compressed Tiny mappings over the normal jar distribution does save you an entire kilobyte of downloading. It's the thought that counts really.

If previously the mappings dependency looked like
```groovy
mappings "net.fabricmc:yarn:19w13a.2"
```
In order to use the compressed form, it would need to be changed to
```groovy
mappings "net.fabricmc:yarn:19w13a.2:tiny@gz"
```
Fairly simple stuff, just like with Enigma. Only without the obvious benefits.


### Running with Enigma mappings from Github
Using Enigma mappings is all well and good, parameter names and all, but it does rely on the zip being hosted on a maven in order to be downloaded. Fortunately, Sin² offers a way of downloading mappings straight from the [Yarn repo](https://github.com/FabricMC/yarn) or indeed any other Github repository directly. This means any pull request you might want to try you can before it is pulled into the main repo. As well as using the main repo's Enigma mappings at all given they're not exported anymore.

If previously the mappings dependency looked like
```groovy
mappings "net.fabricmc:yarn:19w13a.1"
```
In order to use the Github mappings, it would need to be changed to
```groovy
mappings loom.yarnBranch("19w13a") {spec ->
	spec.version = "19w13a-1"
}
//or
mappings loom.yarnCommit("6e610a8") {spec ->
	spec.version = "19w40a-1"
}
//or even
mappings loom.fromBranch("MyOrg/Repo", "myBranch") {spec ->
	spec.group = "my.great.group" //Is the user/organisation's name by default
	spec.name = "Best-Mappings" //Is the repository's name by default
	spec.version = "1.14.4-3"

	spec.forceFresh = true //Force the mappings to be redownloaded even when they haven't changed
}
```
Explicitly forcing the version is important to ensure the correct Intermediaries are chosen, it also allows versioning commits/branches that would otherwise be impossible to update between without changing the mapping group or name. Note that any changed to a chosen branch will be picked up and downloaded when Gradle is run (similar to a `-SNAPSHOT` version), commits however are completely stable even if forced over in the repository's tree.


### Access Transformers
Sin² provides dev time access transformations for making Minecraft classes and methods public (and non-final). For an explaination of how to use this, as well as the runtime component for using the ATs in game, see [here](https://github.com/Chocohead/Fabric-ASM#sailing-the-shenanigans).


### Additional tasks
Sin² adds an additional task type for producing remapping jars from other source sets on top of what the default `jar` task makes. It avoids the gotcha that a [`RemapJar`](https://github.com/Chocohead/fabric-loom/blob/ATs/src/main/java/net/fabricmc/loom/task/RemapJar.java) type task has in that the jar has to be supplied from elsewhere already made. [`RemappingJar`](https://github.com/Chocohead/fabric-loom/blob/ATs/src/main/java/net/fabricmc/loom/task/RemappingJar.java) is an extension of the normal `Jar` task which both remaps the output, and can optionally include the access transformer for the project:
```groovy
task exampleJar(type: RemappingJar, dependsOn: exampleClasses) {
	from sourceSets.example.output
	includeAT = false
}
```
The example source set will now produce a seperate jar which doesn't include the (remapped) access transformer file. Like the normal `Jar` task as many files can be added to the compilation set as desired.


## What's broken?
Ideally nothing, right now there is nothing Sin² knowingly breaks out right. Any issues running the game out of an IDE when using Enigma or gz compressed Yarn mappings are likely down to the `runtimeOnly` configuration being missed and can be fixed as specified above in the [Enigma section](#running-with-enigma-mappings).
