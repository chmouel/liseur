---
name: android-cli
description: Provides instructions for installing and using the `android` CLI. The `android` command-line tool is a critical tool for Android development and helps you create new Android projects, run Android apps on devices, manage and interact with Android virtual devices (including screenshots and UI inspection), manage Android SDK components, look up official Android documentation, and discover and install official Android skills.
---
# Android CLI Specialist

This skill provides instructions for using the `android` CLI tool. The tool includes various commands for creating projects, running applications, interacting with devices, and managing the CLI environment.

## Installation

If the android tool is not in the path, install it. To install run the following command:
Linux x86-64: curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/install.sh | bash
Linux ARM64: curl -fsSL https://dl.google.com/android/cli/latest/linux_arm64/install.sh | bash
Mac Arm: curl -fsSL https://dl.google.com/android/cli/latest/darwin_arm64/install.sh | bash
Mac Intel: curl -fsSL https://dl.google.com/android/cli/latest/darwin_x86_64/install.sh | bash
Windows: curl -fsSL https://dl.google.com/android/cli/latest/windows_x86_64/install.cmd -o "%TEMP%\i.cmd" && "%TEMP%\i.cmd"

## SDK management
To manage the installation of Android SDKs and tools, use the `sdk` command. For example:

- `android sdk install <package>[@<version>]...`: Install specific packages. Multiple packages can be specified, separated by spaces. `<version>` defaults to latest. For example: `android sdk install platforms/android-30@2 platforms/android-34`
- `android sdk update [<pkg-name>]`: Update a specific package or all packages to the latest version.
- `android sdk remove <pkg-name>`: Remove a package from the local SDK.
- `android sdk list --all`: List installed and available SDK packages.

## Project creation
Create projects from templates using the `create` command.

For example: `android create empty-activity --name="My App" --output=./my-app`

## Interacting with Android devices
Use the `android layout` command to inspect the UI layout of an Android application in JSON format.
Use the `android screen` command to visually inspect the UI and obtain bounding coordinates for visual regions.

**IMPORTANT:** Before using `android layout` or `android screen`, you MUST read this reference: [interact.md](references/interact.md)
The reference file [interact.md](references/interact.md) contains instructions for properly interacting with Android devices. Follow its instructions exactly.

### Running journey tests
Journey tests involve device interaction; use the `android layout` and `android screen` commands to evaluate Journeys.
The instructions for device interaction apply here as well.

**IMPORTANT:** Before evaluating a journey, you MUST read this reference: [journeys.md](references/journeys.md)
The reference file [journeys.md](references/journeys.md) contains instructions for correctly evaluating an Android journey. Follow its instructions exactly.

## Doc searching
The `docs` command searches authoritative, high-quality Android developer documentation in the Android Knowledge Base.
By providing a few keywords, this tool will return high quality articles that contain examples or guidance on how to use Android APIs or libraries.
Use this tool to obtain additional information on how to achieve Android-specific tasks or to know more about Android APIs, surfaces, libraries, or devices.

Always use this tool to get the most up-to-date information about Android concepts. Typical good use cases are:
  - Finding migration guides for APIs.
  - Finding examples for APIs.
  - Finding up-to-date information about Android APIs.
  - Finding best practices for Android concepts.

## Running APKs
Use the `run` command to run Android apps.

## Managing emulators

Manage Android Virtual Devices (AVDs) using the `android emulator` command

## Capturing screenshots

Capture an image of the current screen of a connected Android device and output it to a file using the `android screen` command.

## Managing skills

Manage agent skills for Android using the `android skills` command.

## Updating the CLI

Update the Android CLI using the `android update` command.

# `android help` output

Usage: android [-Vhv] [--sdk=PARAM] [COMMAND]
  -h, --help       Shows the help message for the specified command
      --sdk=PARAM  Path to the Android SDK
  -v, --verbose    Enable verbose output for troubleshooting
  -V, --version    Print version information and exit
Commands:
  auth        Authentication commands. Log in/out of Google services for Android
              CLI
  completion  Installs shell autocomplete configuration for Android CLI in the
              current user profile
  create      Creates a new Android project from available templates. You can
              specify the project name, output directory, `minSdk` value, and
              dry-run execution
  describe    Analyzes an Android project to generate descriptive metadata. This
              command identifies and outputs the paths to JSON files that detail
              the project's structure, including build targets and their
              corresponding output artifact locations (such as APKs). This
              information enables other tools and commands to locate build
              artifacts efficiently
  device      Manage physical Android devices. Create remote physical device
              reservations
  docs        Searches and fetches developer documentation from the official
              Android Knowledge Base
  emulator    Manages Android Virtual Devices (AVDs). Includes commands to
              start, stop, list, and view details about emulators
  help        Shows the help information for a specified command
  info        Prints environment information including SDK location, connected
              devices, and configuration variables
  init        Initializes the environment for Android CLI. Sets up required
              configurations, directories, and default skills
  install     Installs an Android app (one or more APKs) on a connected device
              or emulator without activating any components, using incremental
              optimizations for faster deployment than `adb`
  layout      Returns the layout tree of an app
  run         Builds, deploys, and launches an Android app on a connected device
              or emulator
  screen      Captures and inspects the screen of a connected Android device or
              emulator
  sdk         Manages the Android SDK installation. Includes commands to
              install, update, remove, and list available and installed SDK
              packages
  skills      Manages Android CLI skills. Includes commands to install, remove,
              list, and search for skills by keyword
  studio      Connects Android CLI to a running Android Studio instance to
              analyze files, find declarations and usages, render Compose
              previews, and look up library versions
  update      Updates Android CLI to the latest version

auth
          Usage: android auth [-h] [COMMAND]
          Authentication commands. Log in/out of Google services for Android CLI
          
          Options:
            -h, --help       Shows the help message for the specified command
          
          android options:
                --sdk=PARAM  Path to the Android SDK
            -v, --verbose    Enable verbose output for troubleshooting
            -V, --version    Print version information and exit
          Commands:
            login   Log in to Google services for Android CLI
            logout  Log out from Google services for Android CLI
            status  Prints the currently signed-in account

completion
          Usage: android completion [-h] [<shell>]
          Installs shell autocomplete configuration for Android CLI in the
          current user profile
          
          Options:
            -h, --help       Shows the help message for the specified command
          
          android options:
                --sdk=PARAM  Path to the Android SDK
            -v, --verbose    Enable verbose output for troubleshooting
            -V, --version    Print version information and exit
          
          Positional Parameters:
            <shell>  If provided, prints the completion configuration script for
                     the specified shell without installing it. Supports Bash
                     and Zsh

create
          Usage: android create [-h] [--application-id=PARAM] [--force] [--list]
                                [--min-sdk=PARAM] [--name=PARAM] [--namespace=PARAM]
                                [--output=PARAM] [<template-name>]
          Creates a new Android project from available templates. You can
          specify the project name, output directory, `minSdk` value, and
          dry-run execution
          
          Options:
                --application-id=PARAM  The app ID for the app, for example
                                        `com.example.myapp`
                --force                 Allow creating the project in a
                                        non-empty directory, overwriting
                                        existing files
            -h, --help                  Shows the help message for the specified
                                        command
                --list                  List all available templates
                --min-sdk=PARAM         The `minSdk` value supported by the app
                                        (the default value is defined in the
                                        template)
                --name=PARAM            The name of the app, for example `My
                                        Application`
                --namespace=PARAM       The namespace for resources and package
                                        name for Kotlin source files
            -o, --output=PARAM          The destination project directory path
                                        (the default value is `.`)
          
          android options:
                --sdk=PARAM             Path to the Android SDK
            -v, --verbose               Enable verbose output for
                                        troubleshooting
            -V, --version               Print version information and exit
          
          Positional Parameters:
            <template-name>  The template name

describe
          Usage: android describe [-h] [--project_dir=PARAM]
          Analyzes an Android project to generate descriptive metadata. This
          command identifies and outputs the paths to JSON files that detail the
          project's structure, including build targets and their corresponding
          output artifact locations (such as APKs). This information enables
          other tools and commands to locate build artifacts efficiently
          
          Options:
            -h, --help               Shows the help message for the specified
                                     command
                --project_dir=PARAM  The project directory to describe
          
          android options:
                --sdk=PARAM          Path to the Android SDK
            -v, --verbose            Enable verbose output for troubleshooting
            -V, --version            Print version information and exit

device
          Usage: android device [-h] [COMMAND]
          Manage physical Android devices. Create remote physical device
          reservations
          
          Options:
            -h, --help       Shows the help message for the specified command
          
          android options:
                --sdk=PARAM  Path to the Android SDK
            -v, --verbose    Enable verbose output for troubleshooting
            -V, --version    Print version information and exit
          Commands:
            remote  Manage remote physical devices. Includes commands to list,
                    reserve, connect to, and manage remote device reservations

docs
          Usage: android docs [-h] [COMMAND]
          Searches and fetches developer documentation from the official Android
          Knowledge Base
          
          Options:
            -h, --help       Shows the help message for the specified command
          
          android options:
                --sdk=PARAM  Path to the Android SDK
            -v, --verbose    Enable verbose output for troubleshooting
            -V, --version    Print version information and exit
          Commands:
            fetch   Fetches an Android documentation article from a URL starting
                    with `kb://`
            search  Searches Android documentation. Enclose keywords in quotes

emulator
          Usage: android emulator [-h] [COMMAND]
          Manages Android Virtual Devices (AVDs). Includes commands to start,
          stop, list, and view details about emulators
          
          Options:
            -h, --help       Shows the help message for the specified command
          
          android options:
                --sdk=PARAM  Path to the Android SDK
            -v, --verbose    Enable verbose output for troubleshooting
            -V, --version    Print version information and exit
          Commands:
            create  Creates a virtual device
            list    Lists available virtual devices
            remove  Deletes a virtual device
            start   Launches the specified virtual device. This command returns
                    when the emulator is fully started and ready to use
            stop    Stops the specified virtual device

help
          Usage: android help [-h] [COMMAND]
          Shows the help information for a specified command
          
          Options:
            -h, --help       Shows the help message for the specified command
          
          android options:
                --sdk=PARAM  Path to the Android SDK
            -v, --verbose    Enable verbose output for troubleshooting
            -V, --version    Print version information and exit
          
          Positional Parameters:
            COMMAND  The command to show help for

info
          Usage: android info [-h] [<field>]
          Prints environment information including SDK location, connected
          devices, and configuration variables
          
          Options:
            -h, --help       Shows the help message for the specified command
          
          android options:
                --sdk=PARAM  Path to the Android SDK
            -v, --verbose    Enable verbose output for troubleshooting
            -V, --version    Print version information and exit
          
          Positional Parameters:
            <field>  The specific field to print the value of. If omitted,
                     prints all fields

init
          Usage: android init [-h]
          Initializes the environment for Android CLI. Sets up required
          configurations, directories, and default skills
          
          Options:
            -h, --help       Shows the help message for the specified command
          
          android options:
                --sdk=PARAM  Path to the Android SDK
            -v, --verbose    Enable verbose output for troubleshooting
            -V, --version    Print version information and exit

install
          Usage: android install [-h] [--apks=PARAM] [--device=PARAM] [--install-options=PARAM]
                                 [--use-delta-install]
          Installs an Android app (one or more APKs) on a connected device or
          emulator without activating any components, using incremental
          optimizations for faster deployment than `adb`
          
          Options:
                --apks=PARAM             The paths to the APKs, comma separated
                --device=PARAM           The device serial number
            -h, --help                   Shows the help message for the
                                         specified command
                --install-options=PARAM  Additional options or flags to pass to
                                         package manager install (for example,
                                         `-g`, `-d`)
                --use-delta-install      Use fast delta install (speeds up
                                         incremental updates by transferring
                                         only modified code and resources;
                                         default: `true`)
          
          android options:
                --sdk=PARAM              Path to the Android SDK
            -v, --verbose                Enable verbose output for
                                         troubleshooting
            -V, --version                Print version information and exit

layout
          Usage: android layout [-dhp] [--device=PARAM] [--flat] [--full] [--no-idle]
                                [--output=PARAM]
          Returns the layout tree of an app
          
          Options:
                --device=PARAM  The device serial number
            -d, --diff          Deprecated; no-op flag. Will be removed in a
                                future release
                --flat          Returns a flat list instead of a tree
                --full          Returns the full tree, including non-interactive
                                and hidden elements
            -h, --help          Shows the help message for the specified command
                --no-idle       Don't wait for layout idle state when fetching
                                layout
            -o, --output=PARAM  Writes the layout to the specified file or
                                directory. If omitted, prints to standard output
            -p, --pretty        Pretty-prints the returned JSON
          
          android options:
                --sdk=PARAM     Path to the Android SDK
            -v, --verbose       Enable verbose output for troubleshooting
            -V, --version       Print version information and exit

run
          Usage: android run [-h] [--activity=PARAM] [--apks=PARAM] [--debug] [--device=PARAM]
                             [--install-options=PARAM] [--type=PARAM] [--use-delta-install]
          Builds, deploys, and launches an Android app on a connected device or
          emulator
          
          Options:
                --activity=PARAM         The activity name
                --apks=PARAM             The paths to the APKs, comma separated
                --debug                  Run in debug mode
                --device=PARAM           The device serial number
            -h, --help                   Shows the help message for the
                                         specified command
                --install-options=PARAM  Additional options or flags to pass to
                                         package manager install (for example,
                                         `-g`, `-d`)
                --type=PARAM             The component type (`ACTIVITY`,
                                         `WATCH_FACE`, `TILE`, `COMPLICATION`,
                                         `DECLARATIVE_WATCH_FACE`,
                                         `WEAR_WIDGET`)
                --use-delta-install      Use fast delta install (speeds up
                                         incremental updates by transferring
                                         only modified code and resources;
                                         default: `true`)
          
          android options:
                --sdk=PARAM              Path to the Android SDK
            -v, --verbose                Enable verbose output for
                                         troubleshooting
            -V, --version                Print version information and exit

screen
          Usage: android screen [-h] [COMMAND]
          Captures and inspects the screen of a connected Android device or
          emulator
          
          Options:
            -h, --help       Shows the help message for the specified command
          
          android options:
                --sdk=PARAM  Path to the Android SDK
            -v, --verbose    Enable verbose output for troubleshooting
            -V, --version    Print version information and exit
          Commands:
            capture  Outputs the device screen to a PNG
            resolve  Targets UI elements visually. Substitutes bounding box
                     coordinates from an annotated screenshot into a string,
                     replacing all instances of `#N` with the center coordinates
                     of the bounding box labeled `N`

sdk
          Usage: android sdk [-h] [--ignore-outdated-xmls] [--platform=PARAM]
                             [COMMAND]
          Manages the Android SDK installation. Includes commands to install,
          update, remove, and list available and installed SDK packages
          
          Options:
            -h, --help                  Shows the help message for the specified
                                        command
                --ignore-outdated-xmls  When installing or updating packages, do
                                        not update other packages' XML files
                --platform=PARAM        Target platform `<os>_<arch>` (for
                                        example, `linux_x86_64`, `mac_arm64`, or
                                        `windows_x86`); defaults to the current
                                        host
          
          android options:
                --sdk=PARAM             Path to the Android SDK
            -v, --verbose               Enable verbose output for
                                        troubleshooting
            -V, --version               Print version information and exit
          Commands:
            install  Installs SDK packages
            list     Lists installed and available SDK packages
            remove   Removes packages from the SDK
            update   Updates one or all packages to the latest version

skills
          Usage: android skills [-h] [COMMAND]
          Manages Android CLI skills. Includes commands to install, remove,
          list, and search for skills by keyword
          
          Options:
            -h, --help       Shows the help message for the specified command
          
          android options:
                --sdk=PARAM  Path to the Android SDK
            -v, --verbose    Enable verbose output for troubleshooting
            -V, --version    Print version information and exit
          Commands:
            add     Installs a specific skill by its ID to your environment
            find    Searches for available skills in the repository matching a
                    keyword
            list    Lists installed and available skills
            remove  Removes an installed skill by its ID
            update  Updates installed skills

studio
          Usage: android studio [-h] [COMMAND]
          Connects Android CLI to a running Android Studio instance to analyze
          files, find declarations and usages, render Compose previews, and look
          up library versions
          
          Options:
            -h, --help       Shows the help message for the specified command
          
          android options:
                --sdk=PARAM  Path to the Android SDK
            -v, --verbose    Enable verbose output for troubleshooting
            -V, --version    Print version information and exit
          Commands:
            analyze-file            Analyzes a file in Android Studio
            check                   Checks the status of running Android Studio
                                    instances
            find-declaration        Finds the declaration of a symbol
            find-usages             Finds usages of a symbol
            open-file               Opens a file in Android Studio
            render-compose-preview  Renders a Compose preview in Android Studio
            version-lookup          Looks up the latest available versions of
                                    Maven artifacts, Android versions, and SDK
                                    tools

update
          Usage: android update [-h] [--url=PARAM]
          Updates Android CLI to the latest version
          
          Options:
            -h, --help       Shows the help message for the specified command
                --url=PARAM  The URL to download the update from
          
          android options:
                --sdk=PARAM  Path to the Android SDK
            -v, --verbose    Enable verbose output for troubleshooting
            -V, --version    Print version information and exit

