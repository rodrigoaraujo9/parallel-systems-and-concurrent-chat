use std::fs::File;
use std::io::{self, Write};
use std::time::Instant;

pub fn basic_matrix_multiplication(a: &[f64], b: &[f64], side: usize) -> Option<Vec<f64>> {
    if a.len() != side * side || b.len() != side * side {
        return None;
    }
    let mut result = vec![0.0; side * side];
    for i in 0..side {
        for k in 0..side {
            let a_val = a[i * side + k];
            for j in 0..side {
                result[i * side + j] += a_val * b[k * side + j];
            }
        }
    }
    Some(result)
}

pub fn line_matrix_multiplication(a: &[f64], b: &[f64]) -> Option<Vec<f64>> {
    if a.len() != b.len() {
        return None;
    }
    let length = a.len();
    let side_f64 = (length as f64).sqrt();
    if side_f64.fract() != 0.0 {
        return None;
    }
    let side = side_f64 as usize;
    let mut result = vec![0.0; length];
    for i in 0..side {
        for k in 0..side {
            let a_val = a[i * side + k];
            for j in 0..side {
                result[i * side + j] += a_val * b[k * side + j];
            }
        }
    }
    Some(result)
}

pub fn block_matrix_multiplication(a: &[f64], b: &[f64], block_size: usize) -> Option<Vec<f64>> {
    if a.len() != b.len() {
        return None;
    }
    let length = a.len();
    let side_f64 = (length as f64).sqrt();
    if side_f64.fract() != 0.0 {
        return None;
    }
    let side = side_f64 as usize;
    let mut result = vec![0.0; length];

    for ii in (0..side).step_by(block_size) {
        for jj in (0..side).step_by(block_size) {
            for kk in (0..side).step_by(block_size) {
                for i in ii..(ii + block_size).min(side) {
                    for k in kk..(kk + block_size).min(side) {
                        let a_val = a[i * side + k];
                        let res_row = &mut result[i * side..(i + 1) * side];
                        for j in jj..(jj + block_size).min(side) {
                            res_row[j] += a_val * b[k * side + j];
                        }
                    }
                }
            }
        }
    }
    Some(result)
}

fn generate_matrix(size: usize) -> Vec<f64> {
    (0..size * size).map(|i| (i % 10) as f64).collect()
}

fn main() {
    // Create and initialize the CSV file
    let mut csv_file = File::create("results.csv").expect("Failed to create CSV file.");
    writeln!(csv_file, "TestType,Size,Method,BlockSize,Duration")
        .expect("Failed to write CSV header.");

    println!("Select experiment:");
    println!("  1 - Basic and Line Multiplication");
    println!("  2 - Block-Oriented Multiplication");
    println!("  both - Run both experiments");
    println!("Enter your choice:");

    let mut exp_choice = String::new();
    io::stdin()
        .read_line(&mut exp_choice)
        .expect("Failed to read experiment choice");

    match exp_choice.trim() {
        "1" => run_basic_line(&mut csv_file),
        "2" => run_block(&mut csv_file),
        "both" => {
            run_basic_line(&mut csv_file);
            run_block(&mut csv_file);
        }
        _ => println!("Invalid choice. Exiting."),
    }

    println!("\nAll tests completed. Results written to results.csv.");
}

fn run_basic_line(csv_file: &mut File) {
    println!("\n1: Comparing Basic and Line Multiplication");
    for size in (600..=3000).step_by(400) {
        println!("\nProcessing a matrix of size {}x{}...", size, size);
        let a = generate_matrix(size);
        let b = generate_matrix(size);

        // Basic multiplication
        let start = Instant::now();
        let res1 = basic_matrix_multiplication(&a, &b, size)
            .expect("Basic multiplication failed unexpectedly.");
        let duration1 = start.elapsed();
        println!("Basic multiplication completed in: {:.2?}", duration1);
        writeln!(csv_file, "Basic,{},Basic,,{:?}", size, duration1)
            .expect("Failed to write basic multiplication result to CSV.");

        // Line multiplication
        let start = Instant::now();
        let res2 = line_matrix_multiplication(&a, &b)
            .expect("Line multiplication failed unexpectedly.");
        let duration2 = start.elapsed();
        println!("Line multiplication completed in: {:.2?}", duration2);
        writeln!(csv_file, "Basic,{},Line,,{:?}", size, duration2)
            .expect("Failed to write line multiplication result to CSV.");

        // Pattern matching to check results
        match res1.len() == res2.len() && res1.iter().zip(res2.iter()).all(|(x, y)| (x - y).abs() < 1e-6) {
            true => println!("Success: Both methods produced the same result!"),
            false => println!("Warning: The results differ between methods!"),
        }
    }
}

fn run_block(csv_file: &mut File) {
    println!("\n2: Evaluating Block-Oriented Multiplication");
    println!("Enter the number of iterations for each block size test:");
    let mut iter_input = String::new();
    io::stdin()
        .read_line(&mut iter_input)
        .expect("Failed to read iterations count");

    let iterations = match iter_input.trim().parse::<usize>() {
        Ok(num) if num > 0 => num,
        _ => {
            println!("Invalid number of iterations. Using default value 1.");
            1
        }
    };

    for size in (4096..=10240).step_by(2048) {
        println!("\nWorking with a {}x{} matrix:", size, size);
        let a = generate_matrix(size);
        let b = generate_matrix(size);
        for &block_size in &[128, 256, 512] {
            let mut total_secs = 0.0;
            for _ in 0..iterations {
                let start = Instant::now();
                let _ = block_matrix_multiplication(&a, &b, block_size)
                    .expect("Block multiplication failed unexpectedly.");
                total_secs += start.elapsed().as_secs_f64();
            }
            let avg_secs = total_secs / iterations as f64;
            println!(
                "  Block size {:>3} (over {} iterations): average time {:.4} sec",
                block_size, iterations, avg_secs
            );
            writeln!(csv_file, "Block,{},Block,{},{:.4}", size, block_size, avg_secs)
                .expect("Failed to write block multiplication result to CSV.");
        }
    }
}
