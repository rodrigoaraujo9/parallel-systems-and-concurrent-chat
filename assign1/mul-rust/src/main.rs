use std::env;
use std::time::Instant;

#[derive(Debug, Clone)]
pub struct Matrix {
    side: usize,
    data: Vec<f64>,
}

impl Matrix {
    pub fn new(side: usize) -> Self {
        let data = (0..side * side).map(|i| (i % 10) as f64).collect();
        Matrix { side, data }
    }

    pub fn from_vec(side: usize, data: Vec<f64>) -> Option<Self> {
        if data.len() != side * side {
            None
        } else {
            Some(Matrix { side, data })
        }
    }

    pub fn multiply_basic(&self, other: &Matrix) -> Option<Matrix> {
        if self.side != other.side {
            return None;
        }
        let side = self.side;
        let mut result = vec![0.0; side * side];
        for i in 0..side {
            for k in 0..side {
                let a_val = self.data[i * side + k];
                for j in 0..side {
                    result[i * side + j] += a_val * other.data[k * side + j];
                }
            }
        }
        Some(Matrix { side, data: result })
    }

    pub fn multiply_line(&self, other: &Matrix) -> Option<Matrix> {
        if self.side != other.side {
            return None;
        }
        let side = self.side;
        let mut result = vec![0.0; side * side];
        for i in 0..side {
            for k in 0..side {
                let a_val = self.data[i * side + k];
                for j in 0..side {
                    result[i * side + j] += a_val * other.data[k * side + j];
                }
            }
        }
        Some(Matrix { side, data: result })
    }

    pub fn multiply_block(&self, other: &Matrix, block_size: usize) -> Option<Matrix> {
        if self.side != other.side {
            return None;
        }
        let side = self.side;
        let mut result = vec![0.0; side * side];
        for ii in (0..side).step_by(block_size) {
            for jj in (0..side).step_by(block_size) {
                for kk in (0..side).step_by(block_size) {
                    for i in ii..(ii + block_size).min(side) {
                        for k in kk..(kk + block_size).min(side) {
                            let a_val = self.data[i * side + k];
                            for j in jj..(jj + block_size).min(side) {
                                result[i * side + j] += a_val * other.data[k * side + j];
                            }
                        }
                    }
                }
            }
        }
        Some(Matrix { side, data: result })
    }
}

enum Mode {
    Normal,
    Line,
    Block(usize),
}

impl Mode {
    fn from_args(mode_str: &str, maybe_block_size: Option<usize>) -> Option<Self> {
        match mode_str.to_lowercase().as_str() {
            "n" => Some(Mode::Normal),
            "l" => Some(Mode::Line),
            "b" => maybe_block_size.map(Mode::Block),
            _ => None,
        }
    }
}

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() < 3 {
        eprintln!(
            "Usage:\n  For normal and line modes: {} <mode: n|l> <matrix_size>\n  For block mode: {} b <matrix_size> <block_size>",
            args[0], args[0]
        );
        std::process::exit(1);
    }

    let matrix_size: usize = args[2].parse().expect("Invalid matrix size");

    let mode = if args[1].to_lowercase() == "b" {
        if args.len() >= 4 {
            let block_size: usize = args[3].parse().expect("Invalid block size");
            Mode::Block(block_size)
        } else {
            eprintln!("Block mode requires a block size argument.");
            std::process::exit(1);
        }
    } else {
        Mode::from_args(&args[1], None).unwrap_or_else(|| {
            eprintln!(
                "Invalid mode. Use 'n' for normal, 'l' for line, or 'b' for block multiplication."
            );
            std::process::exit(1);
        })
    };

    let a = Matrix::new(matrix_size);
    let b = Matrix::new(matrix_size);

    let start = Instant::now();
    let _result = match mode {
        Mode::Normal => a.multiply_basic(&b),
        Mode::Line => a.multiply_line(&b),
        Mode::Block(bs) => a.multiply_block(&b, bs),
    };
    let duration_sec = start.elapsed().as_nanos() as f64 / 1_000_000_000.0;
    println!("time_sec");
    println!("{}", duration_sec);
}
