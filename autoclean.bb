#!/usr/bin/env bb

(require '[babashka.process :refer [shell process]])
(require '[clojure.java.io :as io])
(require '[clojure.set :as set])
(require '[clojure.string :as str])
(require '[babashka.cli :as cli])
(require '[clojure.edn :as edn])

;; Logging utilities
(def log-levels {:debug "🔍" :info "ℹ️ " :warn "⚠️ " :error "❌" :success "✅"})

(defn log [level & msgs]
  (let [timestamp (.format (java.time.LocalDateTime/now)
                           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))
        prefix (str (get log-levels level " ") " [" timestamp "] ")]
    (println (str prefix (str/join " " msgs)))))

(defn log-section [title]
  (println "\n" (str/join (repeat 80 "=")) "\n"
           "🔷 " title "\n"
           (str/join (repeat 80 "="))))

(defn default-config-path []
  (str (System/getenv "HOME") "/.config/autoclean-k8s/config.edn"))

(defn read-config [path]
  (try
    (let [config (edn/read-string (slurp path))]
      (log :info "Loaded config from" path)
      config)
    (catch Exception e
      (log :error "Failed to load config from" path "-" (.getMessage e))
      (System/exit 1))))

(defn clone-or-fetch-repo
  "Clone a Git repo from SSH URL into a specific folder, or fetch if it exists"
  [ssh-url folder-name]
  (let [folder (io/file folder-name)]
    (log-section (str "Processing repository:" ssh-url "into" folder-name))
    (if (.exists folder)
      (do
        (log :info "Repository exists, fetching updates...")
        (shell {:dir folder-name :out :string :err :string}
               "git fetch --all --prune")
        (log :success "Fetch completed"))
      (do
        (log :info "Cloning repository...")
        (shell {:out :string :err :string}
               (str "git clone --depth=1 " ssh-url " " folder-name))
        (log :success "Clone completed")))))

(defn gitbranches2
  "Set of git remote branches for given repo"
  [repo]
  (log :debug "Fetching git branches for" repo)
  (->> (shell {:out :string :err :string}
              (str "git -C " repo " ls-remote --heads origin"))
       :out
       (re-seq #"refs/heads/([^\n]+)")
       (map second) ;; second is the first capturing group in the reseq (the first is the whole string)
       set))

(defn get-branches-from-resource
  "Get branches from a specific resource type"
  [ns labels resource-type branch-annotation]
  (let [label-selector (str/join "," labels)]
    (log :debug "Fetching" resource-type "for namespace" ns "with labels" label-selector)
    (try
      (as-> (process (str "kubectl get " resource-type " --request-timeout=2s -n " ns
                          " -l " label-selector " -o json")) _
        (process _ {:out :string :err :string}
                 (str "jq -r '.items[] | .metadata.annotations." branch-annotation "'"))
        (deref _)
        (:out _)
        (str/split-lines _)
        (filter #(not= "" %) _)
        (into #{} _))
      (catch Exception e
        (log :warn "Error fetching" resource-type ":" (.getMessage e))
        #{}))))

(defn branchesofdeployments
  "Determine set of branches used by kubernetes deployments/statefulsets"
  [ns labels resource-types branch-annotation]
  (log :debug "Fetching branches from resources in namespace" ns)
  (reduce
   (fn [acc resource-type]
     (set/union acc (get-branches-from-resource ns labels resource-type branch-annotation)))
   #{}
   resource-types))

(defn slug
  "Transform branch name to conform to label rules"
  [args]
  (-> args
      (str/lower-case)
      (str/replace #"[^a-z0-9]" "-")
      ((fn [s] (if (re-matches #"[0-9].*" s) (str "v" s) s)))
      (subs 0 (min 63 (count args)))
      ((fn [s] (if (= (last s) \-) (subs s 0 (- (count s) 1)) s)))))

(defn delete
  "Delete (or show command in simulation mode) deployments, svc and ingress for given app using branch b"
  [ns delete-config b simulation]
  (let [{:keys [labels branch-label]} delete-config
        label-selector (str/join "," labels)
        branch-selector (str branch-label "=" (slug b))
        kubectl-delete-cmd (str "kubectl delete -n " ns " ing,svc,deployments.apps -l " label-selector " -l " branch-selector)]
    (if simulation
      (log :warn "SIMULATION:" kubectl-delete-cmd)
      (do
        (log :warn "EXECUTING:" kubectl-delete-cmd)
        (as-> (shell {:out :string} kubectl-delete-cmd) _
          (:out _)
          (str/split-lines _)
          (doseq [line _]
            (log :info line)))))))

(defn cleanup [repo-path ns get-labels delete-config resource-types branch-annotation branchprefix simulation]
  (log-section (str "Cleaning up kubernetes resources for " repo-path))
  (when simulation
    (log :warn "Running in SIMULATION mode"))

  (let [gitbranches (gitbranches2 repo-path)
        branchesofdeployments (branchesofdeployments ns get-labels resource-types branch-annotation)
        candidates (set/difference branchesofdeployments gitbranches)
        c (count candidates)]

    (log :info "Git branches count:" (count gitbranches))
    (log :info "Deployments/StatefulSets count:" (count branchesofdeployments))

    (if (and (> c 0) (> (count branchesofdeployments) 0))
      (do
        (log :warn "Found" c "candidates for deletion:")
        (doseq [candidate candidates]
          (log :info "- " candidate))
        (doseq [x candidates]
          (delete ns delete-config (str branchprefix x) simulation)))
      (log :success "No cleanup needed"))))

(def cache-dir (str (System/getenv "HOME") "/.cache/autoclean-k8s"))

(defn ensure-cache-dir []
  (let [dir (io/file cache-dir)]
    (when-not (.exists dir)
      (log :debug "Creating cache directory:" cache-dir)
      (.mkdirs dir))))

(defn process-repos [simulation config]
  (log-section "Processing repositories")
  (ensure-cache-dir)
  (doseq [[repo-name repo-config] (:repos config)]
    (let [temp-dir (str cache-dir "/" repo-name)]
      (clone-or-fetch-repo (:repo repo-config) temp-dir)
      (cleanup temp-dir
               (:namespace repo-config)
               (:get (:labelselector repo-config))
               (:delete (:labelselector repo-config))
               (:resource-types repo-config)
               (:branch-annotation repo-config)
               (:branchprefix repo-config)
               simulation))))

(def cli-options
  {:simulation {:default true :coerce :boolean}
   :config {:default (default-config-path)}})

(let [parsedargs (cli/parse-opts *command-line-args* {:spec cli-options})
      {:keys [simulation config]} parsedargs
      config-data (read-config config)]
  (log-section "K8s Resource Cleanup Tool")
  (try
    (process-repos simulation config-data)
    (log :success "Cleanup process completed successfully")
    (catch Exception e
      (log :error "Error during execution:" (.getMessage e))
      (System/exit 1))))
