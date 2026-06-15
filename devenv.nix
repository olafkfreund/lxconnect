{ pkgs, lib, config, inputs, ... }:

{
  # Enable Android SDK
  android = {
    enable = true;
    platforms.version = [ "34" ];
    buildTools.version = [ "34.0.0" ];
  };

  # Enable Java
  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk17;
  };

  # Packages in shell
  packages = [
    pkgs.git
    pkgs.gnumake
  ];

  # Allow android SDK license acceptance
  enterShell = ''
    export NIXPKGS_ACCEPT_ANDROID_SDK_LICENSE=1
  '';
}
