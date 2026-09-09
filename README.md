# kotoba-lang/org-ieee-cp — POSIX `cp`, as a Kotoba command binary

The single-source `cp` from IEEE Std 1003.1, written in `.kotoba` and
compiled to a standalone native executable.

```sh
./cp SRC DST     # copy SRC's contents to DST
./cp SRC DIR     # copy SRC into DIR, under its own name
```

Eleven cases agree with `/bin/cp` on stdout, stderr, exit status **and the
resulting directory tree**.

## The tree is the test

A copy tool that printed nothing and exited 0 would pass an output-only
comparison completely. So after each case both implementations' directories
are hashed file by file and compared.

That is not a precaution, it is load-bearing: replacing the copied content
with a constant fails five cases with **`exit [0 0]` and `err=["" ""]`** —
identical status, identical stderr, differing only in the tree.

Both implementations run in the *same* directory, one after the other, with
the fixtures rebuilt in between. The diagnostics contain absolute paths, so
two parallel trees would differ in stderr for reasons unrelated to the
behaviour under test.

## Three named divergences, each asserted to still be true

The suite does not avoid these; it checks each one still diverges exactly as
described, because a divergence that quietly stops being true is as much a
finding as one that appears.

**1. A newly created destination gets mode 0644.** `/bin/cp` gives it the
source's mode. Measured 2026-09-10: source 755 → dest 755, source 644 → dest
644; ours is 644 either way, so an executable loses its executable bit. The
host's write form passes `0644` to `open(2)`.

*Overwriting* an existing destination agrees exactly, and not by luck —
`open(2)` ignores its mode argument when the file already exists, so the
destination keeps its own mode under both. A 600 destination stayed 600.

**2. Bytes that are not valid UTF-8 cannot be copied.** The content crosses
as a guest *string*, and the host validates every string result as canonical
UTF-8. `/bin/cp` copies such a file fine; this refuses — exit 120, **with no
destination written**. It does not truncate and it does not write mangled
bytes. `cp` is otherwise a byte copier, so this is a real restriction of
domain.

**3. Three or more operands are unsupported.** `/bin/cp` treats the last as
a target directory; this prints the usage and exits 64.

Neither of the first two is the shape of `cp` — both are the shape of the
wire it crosses today. A mode-carrying write form would close the first, a
byte-typed result the second.

## Copying a file onto itself

`cp x x` is refused, matching `/bin/cp`'s message and exit 1. `/bin/cp`
decides this by identity (device and inode), so it also catches two different
spellings of one file; there is no stat form on the wire, so this compares
the resolved paths as strings. It catches `cp x x`; it does not catch
`cp x ./x` or a copy through a symlink.

It is worth checking rather than skipping: without it the destination is
truncated before being written back.

## A path's kind is read from its parent

There is no stat form, so "is this a directory" is answered by browsing the
path's **parent** — the browse wire answers `NAME<TAB>D` per entry. That is
the same technique [`org-ieee-find`](https://github.com/kotoba-lang/org-ieee-find)
uses to walk a tree.

This is what keeps the write form from ever being handed a directory, and
what turns a missing destination parent into a diagnostic: without that
check the case exits **120 with `KEXE_TRAP {:kind :signal :signal :SIGILL}`**
instead of `cp: …: No such file or directory`.

## Capabilities and size

`:cli/args` (38), `:fs/app-data` (35), `:fs/browse` (34), `:io/write-error`
(39). No `:io/write` — `cp` writes nothing to stdout.

The whole file is held in one guest string, so `--string-pool` must exceed
the largest file copied.

## What this is not

No `-R`, `-p`, `-f`, `-i`, `-n`, `-a`, `-v`. One source operand. No copying
of directories, devices, symlinks or hard links. Operands must be absolute
paths inside the packaged scope.
