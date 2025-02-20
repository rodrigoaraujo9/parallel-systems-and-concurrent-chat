// WORKS FOR SQUARE MATRICES
fn on_mult_line(a: &Vec<Vec<f64>>, b: &Vec<Vec<f64>>) -> Option<Vec<Vec<f64>>> {
    let rows_a = a.len();
    let rows_b = b.len();
    if rows_a == 0 || rows_b == 0 {
        return None;
    };
    let cols_a = a[0].len();
    let cols_b = b[0].len();
    if cols_a != rows_b {
        return None;
    };
    let mut temp;
    let mut res: Vec<Vec<f64>> = vec![vec![0.0; cols_b]; rows_a];

    for i in 0..rows_a {
        for k in 0..cols_a {
            temp = a[i][k];
            for j in 0..cols_b {
                res[i][j] += temp * b[k][j];
            }
        }
    }
    Some(res)
}

fn on_mult_line_flat(a: &Vec<f64>, b: &Vec<f64>) -> Option<Vec<f64>> {
    if a.len() != b.len() {
        return None;
    }
    let length = a.len();

    let side_f64 = (length as f64).sqrt();

    if side_f64.fract() != 0.0 {
        return None;
    }

    let side = side_f64 as usize;

    let mut res: Vec<f64> = vec![0.0; length];

    let mut temp;

    for i in 0..side {
        for k in 0..side {
            temp = a[i * side + k];
            for j in 0..side {
                res[i * side + j] += temp * b[k * side + j];
            }
        }
    }

    Some(res)
}

fn on_mult_line_flat_transposed_b(a: &Vec<f64>, b: &Vec<f64>) -> Option<Vec<f64>> {
    if a.len() != b.len() {
        return None;
    }
    let length = a.len();

    let side_f64 = (length as f64).sqrt();

    if side_f64.fract() != 0.0 {
        return None;
    }

    let side = side_f64 as usize;

    let mut res: Vec<f64> = vec![0.0; length];

    let mut b_transposed = vec![0.0; length];

    for i in 0..side {
        for j in 0..side {
            b_transposed[j * side + i] = b[i * side + j];
        }
    }

    for i in 0..side {
        for j in 0..side {
            let mut sum = 0.0;
            for k in 0..side {
                sum += a[i * side + k] * b_transposed[j * side + k];
            }
            res[i * side + j] = sum;
        }
    }

    Some(res)
}

fn main() {
    let matrix_a = vec![
        vec![1.0, 2.0, 3.0],
        vec![4.0, 5.0, 6.0],
        vec![7.0, 8.0, 9.0],
    ];

    let matrix_b = vec![
        vec![9.0, 8.0, 7.0],
        vec![6.0, 5.0, 4.0],
        vec![3.0, 2.0, 1.0],
    ];

    let a = vec![
        1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0,
    ];

    let b = vec![
        1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
    ];

    match on_mult_line_flat(&a, &b) {
        Some(result) => {
            println!("Matrix multiplication result for flat vector representation:");
            for i in 0..4 {
                println!("{:?}", &result[i * 4..(i + 1) * 4]);
            }
        }
        None => println!("Invalid matrix input!"),
    }

    match on_mult_line_flat_transposed_b(&a, &b) {
        Some(result) => {
            println!("Matrix multiplication result for flat vector representation with transposed b for better cache locality:");
            for i in 0..4 {
                println!("{:?}", &result[i * 4..(i + 1) * 4]);
            }
        }
        None => println!("Invalid matrix input!"),
    }

    match on_mult_line(&matrix_a, &matrix_b) {
        Some(result) => {
            println!("Matrix Multiplication:");
            for row in result {
                println!("{:?}", row);
            }
        }
        None => println!("Error: Matrices have different dimensions."),
    }
    /*
    match sum_matrices(&matrix_a, &matrix_b) {
        Some(result) => {
            println!("Matrix Sum:");
            for row in result {
                println!("{:?}", row);
            }
        }
        None => println!("Error: Matrices have different dimensions."),
    }
    */
}

impl Solution {
    fn checkrow(board: Vec<Vec<char>>, index: usize) -> bool {
        let size = board.len();

        if index > size {
            return False;
        }

        True
    }
    pub fn is_valid_sudoku(board: Vec<Vec<char>>) -> bool {}
}
