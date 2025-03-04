---
title: Contributing to Metabase
redirect_from:
  - /docs/latest/developers-guide/contributing
---

# Contributing to Metabase

## Thank you

First off, thanks for your interest in Metabase and for wanting to contribute!

In this guide, we'll discuss how Metabase is built. This should give you a good sense of our process and where you might want to fit in.

In general, we like to have an open issue for every pull request as a place to discuss the nature of any bug or proposed improvement. Each pull request should address a single issue, and contain both the fix as well as a description of the pull request and tests that validate that the PR fixes the issue in question.

For bug fixes, please submit the pull request to target the `master` branch. From time to time, our team will backport selected critical bug fixes to the stable/release branch.

For significant feature additions, it is expected that discussion will have taken place in the attached issue. Any feature that requires a major decision to be reached will need to have an explicit design document written. The goals of this document are to make explicit the assumptions, constraints and tradeoffs any given feature implementation will contain. The point is not to generate documentation but to allow discussion to reference a specific proposed design and to allow others to consider the implications of a given design.

## Contributor License Agreement

We don't like getting sued, so before merging any pull request, we'll need each person contributing code to sign a [Contributor License Agreement](https://docs.google.com/a/metabase.com/forms/d/1oV38o7b9ONFSwuzwmERRMi9SYrhYeOrkbmNaq9pOJ_E/viewform).

## What we're trying to build

Metabase is all about letting non-technical users get access to their organization's data. We're trying to maximize the amount of power that can be comfortably used by someone who understands their business, is quantitatively bent, but probably only comfortable with Excel.

It's important to keep in mind these goals of the Metabase project. Many times
proposals will be marked "Out of Scope" or otherwise deprioritized. This doesn't mean the proposal isn't useful, or that we wouldn't be interested in seeing it done as a side project or as an experimental branch. However, it does mean that we won't point the core team or contributors to it in the near term. Issues that are slightly out of scope will be kept open in case there is community support (and ideally contributions).

To get a sense for the end goals, make sure to read the [Zen of Metabase](https://github.com/metabase/metabase/blob/master/zen.md).

## Our product process:

The core team runs a pretty well defined product process. It is actively being tweaked, but the below is a pretty faithful description of it at the time of writing. You should have a clear idea of how we work before jumping in with a PR.

### A) Identify product needs from the community

We actively look for new feature ideas from our community, user base and our own use of Metabase internally. We concentrate on the underlying _problem_ or _need_ as opposed to requests for specific features. While sometimes suggested features are built as requested, often we find that they involve changes to existing features, and perhaps an entirely different solution to the underlying problem. These will typically be collected in a number of issues, and tagged [Proposal](https://github.com/metabase/metabase/labels/.Proposal)

### B) Synthesize these needs into a concrete feature

We typically will collect a group of issues or suggestions into a new topline feature concept. Typically we'll create a working document that collects all "Open Questions" regarding to what the feature is meant to do, and more importantly not do. We'll chat with our users, maybe do in depth interviews and generally try to tightly define the feature. If a feature seems like it will need time to be discussed and scoped, it will be tagged [Proposal/Being Discussed](https://github.com/metabase/metabase/labels/.Proposal%2FBeing%20Discussed) to signify that it is still actively under discussion.

### C) Design the feature

Once a feature has been defined, typically it will be taken on by a product designer. Here, they will produce low fi mocks, get feedback from our users and community, and iterate.

Once the main UX flows have been dialed in, there will be a hi-fidelity visual design.

Features that are ready for design are tagged [Design Needed](https://github.com/metabase/metabase/labels/.Design%20Needed). Once a feature has had a reasonably complete visual design it should be tagged [Help Wanted](https://github.com/metabase/metabase/labels/.Help%20Wanted).

### D) Build the feature

Once a feature is tagged [Help Wanted](https://github.com/metabase/metabase/labels/.Help%20Wanted), it is considered ready to be built. A core team member (or you, awesomely helpful person that you are) can start working on it.

If you're building something that users will see in Metabase, please refer to the Style Guide (found at `https://storybook.metabase.com`) to learn how and when to use various Metabase UI elements.

Once one or more people have started to work on a feature, it should be marked [In Progress](https://github.com/metabase/metabase/labels/.In%20Progress). Once there is a branch+some code, a pull request is opened, linked to the feature + any issues that were pulled together to inform the feature.

### E) Verification and merging

All PRs that involve more than an insignificant change should be reviewed. See our [Code Review Process](./developers-guide/code-reviews.md).

If all goes well, the feature gets coded up, verified and then the pull request gets merged! High-fives all around.

If there are tests missing, code style concerns or specific architectural issues in the pull request, they should be fixed before merging. We have a very high bar on both code and product quality and it's important that this be maintained going forward, so please be patient with us here.

## Ways to help

The starting point would be to get familiar with Metabase the product, and know your way around. If you're using it at work, that's great! If not, [download Metabase](https://www.metabase.com/start/oss/) and play around with it. Read the docs and generally get a feel for the flow of the product.

Here are some ways you can help, in order of increasing coordination + interaction with us:

### Help with identifying needs and problems Metabase can solve

If you want to help, try out Metabase. Use it at your company, and report back the things you like, dislike and any problems you run into. Help us understand your data model, required metrics and common usage patterns as much as you can. This information directly affects the quality of the product. The more you tell us about the kinds of problems you're facing, the better we'll be able to address them.

### Help us triage and support other users

Spend time on discourse.metabase.com and on new issues and try to reproduce the bugs reported. For people having trouble with their databases where you have significant knowledge, help them out. Who knows, maybe they'll end up helping you with something in the future.

It is helpful if you understand our [prioritization framework](https://github.com/metabase/metabase/wiki/Bug-Prioritization) when responding.

### Tell your friends

Let your friends know about Metabase. Start a user group in your area. [Tweet about us](http://twitter.com/metabase). Blog about how you're using Metabase, and share what you've learned.

### Fix bugs

By our definition, "Bugs" are situations where the program doesn't do what it was expected to according to the design or specification. These are typically scoped to issues where there is a clearly defined correct behavior. It's usually safe to grab one of these, fix it, and submit a PR (with tests!). These will be merged without too much drama unless the PR touches a lot of code. Don't be offended if we ask you to make small modifications or add more tests. We're a bit OCD on code coverage and coding style.

### Help with Documentation

There are a lot of docs, which means keeping them up to date is hard. If you notice inconsistencies, errors, or outdated information, please help us keep them current!

Note that **we cannot accept translations for documentation at this time**. We support [in-app translations](./configuring-metabase/localization.md), and only support languages that have 100% coverage. But 1) the in-app text is orders of magnitude shorter than our docs, 2) it changes at a slower pace, and 3) we have a lot of people help out. We may consider supporting docs in multiple languages in the future, but for now we need to focus our resources on improving our existing documentation (and expanding it to include all of the new features we're adding).

### Working on features

Some features, eg Database drivers, don't have any user facing pixels. These are a great place to start off contributing as they don't require as much communication, discussions about tradeoffs and process in general.

In situations where a design has already been done, we can always use some help. Chime in on a pull request or an issue and offer to help.

Generally speaking, any issue in [Help Wanted](https://github.com/metabase/metabase/labels/.Help%20Wanted) is fair game.

### #YOLO JUST SUBMIT A PR

If you come up with something really cool, and want to share it with us, just submit a PR. If it hasn't gone through the above process, we probably won't merge it as is, but if it's compelling, we're more than willing to help you via code review, design review and generally OCD nitpicking so that it fits into the rest of our codebase.
