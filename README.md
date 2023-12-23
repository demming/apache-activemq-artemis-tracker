# Repository Fork

## Motivation

This fork of the upstream [Apache ActiveMQ Artemis](https://github.com/apache/activemq-artemis) repository serves as a cached safeguard checkpoint for GitHub Actions to build container images and push them to GHCR and Docker Hub.

Upstream's [official Docker Hub repository][official-dockerhub] does not provide images built for the various [ARM microarchitectures][arm], in particular,

- the 64-bit [ARMv8 microarchitectures][armv8], such as the ARM Cortex chipsets, Apple Mac M1, M2, M3 series, Apple's A7-A16 (e.g., Bionic) chipsets, or the Ampere and similar cloud-hosted CPU architectures, or
- the 32-bit [ARMv7 microarchitectures][armv7] used, e.g., in Raspberry Pi SoCs prior to Raspberry Pi 4.

## Workflow

The intended workflow is to have GitHub Actions in the dedicated [container-builder repository][builder]

1. track the upstream branch,
2. in case of a version bump or a commit to `origin/main`, pull from and open a PR for a merge of the main branch in the upstream repo into the `upstream` branch here,
3. alert the repository owner of the PR,
4. have the repository owner approve and either auto-merge or merge the PR to the `main` branch here, and finally
5. build and push the new image to the container registries.

This semi-automated CI workflow enables manual halting, *potentially* applying changes, and evaluating the necessity of the update to the target container repository.
Through the PR approval, it serves as a manual approval mechanism for pulling upstream changes into the target container repositories.

The [builder repository][builder] has a workflow to auto-build 

1. `upstream-release-bindist`: the upstream bindist release (as provided by the upstream build tools), verbatim, bypassing this repository,
2. `upstraem-local-bindist`: the upstream `main` branch but through a local bindist built verbatim using GitHub Actions, bypassing this repository,
3. `fork-approved`: from the `main` branch of this repository.

I am making only the container repositories public that are built bypassing this repository.

Private patches and experimental changes are tracked in a separate private repository, which, in turn, is integrated into a private CI pipeline.


# Diclaimer

I assume there is some interest in having ARM container images for Artemis. There were some individual efforts to make it available to everyone. For as long as the upstream does not push their own ARM builds, I am trying to help out as well. But the results are intended for my own private purposes only.

This is a private effort. No warranty, no responsibility, implicit or explicit, use at your own risk. Do your own due diligence. Applying Apache License on my deltas.

Everything made publicly available here is open-source and presented in the most transparent way feasible to me, and is limited by the upstream license.


[official-dockerhub]: https://hub.docker.com/r/apache/activemq-artemis
[arm]: https://en.wikipedia.org/wiki/ARM_architecture_family#Cores
[armv8]: https://en.wikipedia.org/wiki/AArch64
[armv7]: https://en.wikipedia.org/wiki/Comparison_of_ARM_processors#ARMv7-A
[builder]: https://github.com/demming/apache-activemq-artemis-container-builder


# Administration Console

Without any change, running the container will provide you with a fancy UI console out of the box, which can be reached at http://127.0.0.1:8161/console/ (substitute your actual host IP address or name)


![image](https://github.com/demming/apache-activemq-artemis-tracker/assets/5727019/521ec9d2-1206-4ec3-a07b-c4c4e906ab7a)



# Benchmarks

* [ ] Post some recent benchmarks covering Artemis, Pulsar, RabbitMQ, Kafka, and some other messaging middleware systems.


____

What follows is the snapshot of the [README.md](https://github.com/apache/activemq-artemis/blob/main/README.md) from the upstream repository at the time of the change in the present forking repository.

> # Welcome to Apache ActiveMQ Artemis
>
> ActiveMQ Artemis is the next generation message broker from Apache ActiveMQ.
>
> ## Getting Started
>
> See the [User Manual](https://activemq.apache.org/components/artemis/documentation/latest/) for an in-depth explanation of all aspects of broker configuration and behavior.
> The [ActiveMQ Artemis Examples](https://github.com/apache/activemq-artemis-examples) repository contains over 90 examples demonstrating many of the client and broker features.
>
> ## How to Build, etc.
>
> See the [Hacking Guide](https://activemq.apache.org/components/artemis/documentation/hacking-guide/) for details about modifying the code, building the project, running tests, IDE integration, etc.
>
># Migrate from ActiveMQ "Classic"
>
> See the [Migration Guide](https://activemq.apache.org/components/artemis/migration-documentation/) for information about the architectural and configuration differences between ActiveMQ "Classic" (i.e. 5.x) and ActiveMQ Artemis.
>
> ## Report an Issue
>
> See [our website](https://activemq.apache.org/issues) for details on how to report an bug, request a feature, etc.
