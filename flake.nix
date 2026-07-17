{
  description = "lxconnect - Android to Linux Desktop Bridge";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        pythonEnv = pkgs.python3.withPackages (ps: with ps; [
          pygobject3
        ]);

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          cmdLineToolsVersion = "11.0";
          platformToolsVersion = "37.0.0";
          buildToolsVersions = [ "34.0.0" ];
          platformVersions = [ "34" ];
          abiVersions = [ ]; # No emulator system images (reduces download size significantly)
          includeEmulator = false;
          includeSystemImages = false;
          includeNDK = false;
        };
      in
      {
        packages.default = pkgs.stdenv.mkDerivation {
          name = "lxconnect";
          src = ./.;

          nativeBuildInputs = [
            pkgs.wrapGAppsHook4
            pkgs.gobject-introspection
            pkgs.makeWrapper
          ];

          buildInputs = [
            pkgs.gtk4
            pythonEnv
            pkgs.libnotify
            pkgs.qrencode
            pkgs.zenity # inline-reply prompt for desktop notification actions
          ];

          installPhase = ''
            mkdir -p $out/bin $out/share/lxconnect
            
            cp daemon/main.py $out/share/lxconnect/main.py
            cp daemon/gui.py $out/share/lxconnect/gui.py
            
            # CLI wrapper
            cat > $out/bin/lxconnect <<EOF
            #!/bin/sh
            exec ${pythonEnv}/bin/python $out/share/lxconnect/main.py "\$@"
            EOF
            
            # GUI wrapper
            cat > $out/bin/lxconnect-gui <<EOF
            #!/bin/sh
            exec ${pythonEnv}/bin/python $out/share/lxconnect/gui.py "\$@"
            EOF
            
            chmod +x $out/bin/lxconnect
            chmod +x $out/bin/lxconnect-gui
          '';

          postFixup = ''
            # Make sure the CLI can find notify-send, qrencode and zenity
            wrapProgram $out/bin/lxconnect \
              --prefix PATH : "${pkgs.libnotify}/bin:${pkgs.qrencode}/bin:${pkgs.zenity}/bin"
          '';
        };

        devShells.default = pkgs.mkShell {
          buildInputs = [
            self.packages.${system}.default
            androidComposition.androidsdk
            pkgs.jdk17 # Matches compileOptions target in build.gradle
          ];
          shellHook = ''
            export ANDROID_HOME="${androidComposition.androidsdk}/libexec/android-sdk"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export JAVA_HOME="${pkgs.jdk17.home}"
          '';
        };

        apps = {
          default = flake-utils.lib.mkApp { drv = self.packages.${system}.default; exePath = "/bin/lxconnect"; };
          gui = flake-utils.lib.mkApp { drv = self.packages.${system}.default; exePath = "/bin/lxconnect-gui"; };
        };
      }
    );
}
