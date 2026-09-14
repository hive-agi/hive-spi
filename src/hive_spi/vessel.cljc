(ns hive-spi.vessel
  "Narrow vessel rendering contract. Packages emit neutral operations; editors implement delivery.
   This port conveys rendering authority only, never editor evaluation or permission approval.")

(defonce ^:private defined? (atom false))

(when (compare-and-set! defined? false true)
  (defprotocol IRenderer
    (renderer-id [this] "Stable keyword or string identifying this renderer.")
    (render! [this ops]
      "Deliver a vector of neutral hive-vessel operations.
       Return {:ok value} or {:error diagnostic}; expected failures must not throw.
       Implementations must not interpret observations as grants or execute arbitrary editor code.")))
