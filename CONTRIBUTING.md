# Contributing

Hi there! We're delighted that you'd like to contribute to this project.
It has been generous collaboration from many people all over the world
that has made it possible so far, and your help is key to keeping it
great.

First of all, we would *love* to hear from you! We have no way of knowing who has discovered, explored, downloaded and tried Beat Link.
So if you have, please write a quick note on the [Beat Link Trigger Zulip channel][zulip] to let us know! Even if it is only to explain why it didn’t quite work for you.

For more about how to find help, see [Getting Help](GET_HELP.adoc).

Contributions to this project are [released][contributions-released] to the public under the [project’s open source license](LICENSE.md).

This project adheres to the [Contributor Covenant Code of Conduct][covenant].
By participating, you are expected to uphold this code.

## Getting started

Before you can start contributing to Beat Link, you'll need to set up
your environment first. Fork and clone the repo and install
[Maven][maven] to install the project dependencies and manage builds.
We find [IntelliJ IDEA][idea] (even the free Community Edition) an
incredibly productive environment for Java work, but use whatever IDE
or editor works best for you (we always have [GNU Emacs][emacs] open
too).

For testing you are going to want some Pro DJ Link hardware on your
network, and a wired network is necessary for realistic performance.
If you will be trying to analyze the protocols, you will probably want
to invest in an inexpensive managed switch, to let you span (mirror)
ports, so you can listen in on the traffic players and mixers send
between themselves. The [Netgear GS105Ev2][switch] has worked great
for us.

> [!IMPORTANT]
> Before doing any significant work, we strongly encourage you to discuss your ideas in the [Zulip channel][zulip].
> This will help make sure you’re on the right track, could save you a lot of effort, and ensure alignment with the overall project philosophy and goals.

### Code from AI / LLM Tools

If you are planning to contribute code, please refrain from copy and pasting it from “AI” code assistants and large language models.
It is fine to use those as an inspiration, or examples of the ways something could be done, but always write your own code from scratch if you are going to submit it to the project.

> [!WARNING]
> Code from online coding assistants may be plagiarized from other projects with incompatible licenses (and there is no way to know), and may include subtle bugs.
> It is not suitable for submission to Deep Symmetry projects.

## Giving back

Once you have something working you’d like to share, you can open a
[pull request][pulls].

Or if you simply have an idea, or something that you wish worked differently, feel free to discuss it in the [Beat Link Trigger Zulip channel][zulip], and if directed to do so by the community there, open an [issue][issues].

## Maintainers

Beat Link is primarily maintained by [@brunchboy][brunchboy].

## License

<a href="https://deepsymmetry.org"><img align="right" alt="Deep Symmetry" src="assets/DS-logo-github.png" width="250" height="150"></a>

Copyright © 2016–2025 [Deep Symmetry, LLC](http://deepsymmetry.org)

Distributed under the [Eclipse Public License
2.0](https://opensource.org/licenses/EPL-2.0). By using this software
in any fashion, you are agreeing to be bound by the terms of this
license. You must not remove this notice, or any other, from this
software. A copy of the license can be found in
[LICENSE.md](LICENSE.md) within this project.


[brunchboy]: https://github.com/brunchboy
[contributions-released]: https://help.github.com/articles/github-terms-of-service/#6-contributions-under-repository-license
[covenant]: https://contributor-covenant.org/
[deep-symmetry]: https://deepsymmetry.org
[emacs]: https://www.gnu.org/software/emacs/
[idea]: https://www.jetbrains.com/idea/
[issues]: https://github.com/Deep-Symmetry/beat-link/issues
[maven]: https://maven.apache.org
[pulls]: https://github.com/Deep-Symmetry/beat-link/pulls
[switch]: https://smile.amazon.com/gp/product/B00HGLVZLY/
[zulip]: https://deep-symmetry.zulipchat.com/#narrow/stream/275322-beat-link-trigger
