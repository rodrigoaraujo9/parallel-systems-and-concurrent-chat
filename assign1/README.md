# Running the C++ Matrix Multiplication Implementation

## Compilation and Execution

To compile and run the C++ implementation, use the following commands:

For algorithm 1:
```sh
$ ./script.sh 1 <iterations>
```

For algorithm 2:
```sh
$ ./script.sh 2 <iterations> <p/n>
```

For algorithm 3:
```sh
$ ./script.sh 3 <iterations> <block_size>
```

To execute all possible tests:
```sh
$ ./script.sh 4 <iterations>
```

### Explanation

- Compiles the `C/C++` code with the flag `O2` and executes the shell script.
- `<algorithm>`: Choose the algorithm you want to perform.
  - `1`: Simple Matrix Multiplication
  - `2`: Line Matrix Multiplication
    - `<parallel>`: Choose to run with or without parallelism
  - `3`: Block Matrix Multiplication
    - `<block_size>`: Choose the block size
- `<iterations>`: Choose how many iterations to perform.
---

# Running the Rust Matrix Multiplication Implementation

## Compilation and Execution

To compile and run the Rust implementation, use the following commands:

```sh
cargo build --release
cargo run --release
```

### Explanation

- `cargo build --release` compiles the Rust project in **release mode**, optimizing for speed.
- `cargo run --release` builds and executes the optimized binary.

### Alternative Debug Mode

If you want to run the Rust implementation in **debug mode** (slower but with debug symbols), use:

```sh
cargo build
cargo run
```

This mode is useful for debugging but not recommended for performance testing.

---

## Additional Notes

- Ensure you have `g++` installed for the C++ version.
- Make sure you have `Rust` and `Cargo` installed for the Rust version. You can install Rust via:
  ```sh
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
  ```
- If you experience permission issues, try running `chmod +x <file>` before executing the file.
- To clean build artifacts in Rust, use `cargo clean`.

Let me know if you need additional details!
