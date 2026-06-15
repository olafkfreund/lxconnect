{
  description = "lxconnect - Android to Linux Desktop Bridge";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        
        pythonEnv = pkgs.python3.withPackages (ps: with ps; [
          pygobject3
        ]);
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
            # Make sure the CLI can find notify-send
            wrapProgram $out/bin/lxconnect \
              --prefix PATH : "${pkgs.libnotify}/bin"
          '';
        };

        devShells.default = pkgs.mkShell {
          buildInputs = [
            self.packages.${system}.default
          ];
        };

        apps = {
          default = flake-utils.lib.mkApp { drv = self.packages.${system}.default; exePath = "/bin/lxconnect"; };
          gui = flake-utils.lib.mkApp { drv = self.packages.${system}.default; exePath = "/bin/lxconnect-gui"; };
        };
      }
    );
}
