{ pkgs ? import <nixpkgs> {
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  }
}:

let
  androidComposition = pkgs.androidenv.composeAndroidPackages {
    cmdLineToolsVersion = "11.0";
    platformToolsVersion = "37.0.0";
    buildToolsVersions = [ "34.0.0" ];
    platformVersions = [ "34" ];
    abiVersions = [ ]; # No emulator system images (reduces download size significantly)
    useGoogleAPIs = false;
    useGoogleTVAddOns = false;
    includeEmulator = false;
    includeSources = false;
    includeSystemImages = false;
    includeNDK = false;
  };
in
pkgs.mkShell {
  name = "minimal-android-sdk-shell";
  buildInputs = [
    androidComposition.androidsdk
    pkgs.jdk17 # Matches compileOptions target in build.gradle
    pkgs.git
  ];
  shellHook = ''
    export ANDROID_HOME="${androidComposition.androidsdk}/libexec/android-sdk"
    export ANDROID_SDK_ROOT="${androidComposition.androidsdk}/libexec/android-sdk"
    export JAVA_HOME="${pkgs.jdk17.home}"
  '';
}
