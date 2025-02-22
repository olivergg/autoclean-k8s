# autoclean-k8s

A Babashka script to clean up Kubernetes resources associated with deleted Git branches.

## Overview

This tool helps maintain clean Kubernetes clusters by automatically removing resources (deployments, statefulsets, services, ingresses) that were created for Git branches that no longer exist.

It works by:
1. Checking Git repositories for existing branches
2. Comparing with deployed resources in Kubernetes
3. Cleaning up resources that correspond to deleted branches

## Prerequisites

- [Babashka](https://github.com/babashka/babashka#installation)
- `kubectl` with access to your cluster
- `git` command line tool
- `jq` command line tool (might be removed later)

## Installation

1. Clone this repository
2. Make the script executable:
```bash
chmod u+x k8s-branch-cleanup.clj
```

## Configuration

Create a configuration file in EDN format at `~/.config/k8s-cleanup/config.edn`:

```clojure
{:repos 
  {"app1"
    {:repo "git@github.com:org/app1.git"
     :namespace "apps"
     :labelselector {:get ["app=myapp"]
                    :delete {:labels ["app=myapp"]
                            :branch-label "branch"}}
     :branch-annotation "gitbranch"
     :resource-types ["deployments.apps" "statefulsets.apps"]
     :branchprefix ""}}}
```

Configuration options:
- `repo`: Git SSH URL
- `namespace`: Kubernetes namespace
- `labelselector.get`: Labels to identify resources to check
- `labelselector.delete`: Labels for resources to delete
  - `labels`: Base labels for resources
  - `branch-label`: Label key containing branch name
- `branch-annotation`: Annotation key containing Git branch name
- `resource-types`: Types of Kubernetes resources to check
- `branchprefix`: Optional prefix added to branch names

## Usage

Run in simulation mode (default, no deletions):
```bash
./k8s-branch-cleanup.clj
```

Run with actual deletions:
```bash
./k8s-branch-cleanup.clj --simulation false
```

Use custom config file:
```bash
./k8s-branch-cleanup.clj --config /path/to/config.edn
```

## How it Works

1. For each repository in the config:
   - Clones/updates the Git repository
   - Gets list of current branches
   - Checks Kubernetes resources matching configured labels
   - Identifies resources using branches that no longer exist
   - Deletes matching resources (deployments, statefulsets, services, ingresses)

2. Resources are identified using:
   - Namespace
   - Label selectors
   - Branch name (from annotation)

3. Deletion is performed using kubectl with label selectors

## Safety Features

- Simulation mode enabled by default
- Detailed logging of all operations
- Confirmation of deletions in logs
- Resource timeout limits
- Error handling with descriptive messages

## Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

## License

[GPL3](LICENSE)

