// The exact ODnerf datapatch provisioned for selected workers before the native server loader starts.
package Extinction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Nerf {
    private static final String FILE_NAME = "extinction-nerf.hjson";
    private static final String PATCH = """
            name: "[25/50/50/100] ODnerf"
            block: {
              overdrive-projector: {
                speedBoost: 1.25
                speedBoostPhase: 0.2
                requirements: {
                  0: {
                    amount: 150
                  }
                  1: {
                    amount: 100
                  }
                  2: {
                    amount: 100
                  }
                  3: {
                    amount: 60
                  }
                }
                consumes: {
                  power: {
                    usage: 10
                  }
                }
                buildTime: 352.5
              }
              overdrive-dome: {
                speedBoost: 1.5
                requirements: {
                  4: {
                    amount: 150
                  }
                  0: {
                    amount: 250
                  }
                  1: {
                    amount: 160
                  }
                  2: {
                    amount: 160
                  }
                  3: {
                    amount: 100
                  }
                }
                requirements.+: [
                  phase-fabric/20
                ]
                consumes: {
                  power: {
                    usage: 25
                  }
                }
                description: Consumes huge amounts of power to significantly increase the speed of nearby buildings. Requires phase fabric and silicon to operate. Does not stack.
                buildTime: 838
              }
              phase-weaver: {
                requirements.+: [
                  plastanium/40
                ]
                buildTime: 322
              }
              exponential-reconstructor: {
                requirements: {
                  4: {
                    amount: 225
                  }
                  0: {
                    amount: 1000
                  }
                  1: {
                    amount: 1000
                  }
                  2: {
                    amount: 425
                  }
                  3: {
                    amount: 500
                  }
                  5: {
                    amount: 300
                  }
                }
                buildTime: 3574.5
                constructTime: 6000
              }
              tetrative-reconstructor: {
                requirements: {
                  0: {
                    amount: 2000
                  }
                  1: {
                    amount: 500
                  }
                  2: {
                    amount: 1500
                  }
                  3: {
                    amount: 300
                  }
                  4: {
                    amount: 300
                  }
                  5: {
                    amount: 400
                  }
                }
                buildTime: 4939
                constructTime: 9000
              }
              surge-smelter: {
                requirements.+: [
                  plastanium/50
                ]
                buildTime: 262
              }
            }
            unit: {
              navanax: {
                weapons: {
                  0.bullet.timeIncrease: 2
                  1.bullet.timeIncrease: 2
                }
              }
              quasar: {
                health: 500
                armor: 7
                boostMultiplier: 1.7
                descentSpeed: 0.00555
                riseSpeed: 0.00555
              }
              vela: {
                descentSpeed: 0.00555
                riseSpeed: 0.00555
                boostMultiplier: 2
              }
            }
            """;

    private Nerf() {
    }

    // Newer servers migrate patches into assets/patches; clear both managed paths before reuse.
    public static void provision(File workerConfig, boolean nerf) throws IOException {
        Path legacy = workerConfig.toPath().resolve("patches").resolve(FILE_NAME);
        Path current = workerConfig.toPath().resolve("assets/patches").resolve(FILE_NAME);
        Files.deleteIfExists(legacy);
        Files.deleteIfExists(current);
        if (nerf) {
            Files.createDirectories(legacy.getParent());
            Files.writeString(legacy, PATCH, StandardCharsets.UTF_8);
        }
    }
}
