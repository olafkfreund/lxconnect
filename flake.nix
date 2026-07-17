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
          pygobject3 # GTK4 desktop client (gui.py)
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
            pythonEnv
            pkgs.gtk4
            pkgs.libnotify
            pkgs.qrencode
            pkgs.zenity # inline-reply prompt for desktop notification actions
            pkgs.openssl # ephemeral cert for the HTTPS pairing server
          ];

          # Wrap manually (below) so the CLI gets tool PATHs and the GUI gets both
          # tool PATHs and the GObject/GTK env (gappsWrapperArgs).
          dontWrapGApps = true;

          installPhase = ''
            mkdir -p $out/bin $out/share/lxconnect
            cp daemon/main.py daemon/mcp_client.py daemon/gui.py $out/share/lxconnect/

            cat > $out/bin/lxconnect <<EOF
            #!/bin/sh
            exec ${pythonEnv}/bin/python $out/share/lxconnect/main.py "\$@"
            EOF

            cat > $out/bin/lxconnect-gui <<EOF
            #!/bin/sh
            exec ${pythonEnv}/bin/python $out/share/lxconnect/gui.py "\$@"
            EOF

            chmod +x $out/bin/lxconnect $out/bin/lxconnect-gui
          '';

          postFixup = ''
            wrapProgram $out/bin/lxconnect \
              --prefix PATH : "${pkgs.libnotify}/bin:${pkgs.qrencode}/bin:${pkgs.zenity}/bin:${pkgs.openssl}/bin"

            wrapProgram $out/bin/lxconnect-gui \
              --prefix PATH : "${pkgs.libnotify}/bin:${pkgs.qrencode}/bin:${pkgs.zenity}/bin:${pkgs.openssl}/bin" \
              "''${gappsWrapperArgs[@]}"
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
