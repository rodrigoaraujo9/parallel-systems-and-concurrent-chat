// WORKS FOR SQUARE MATRICES
pub fn on_mult_line(a: &Vec<Vec<f64>>, b: &Vec<Vec<f64>>) -> Option<Vec<Vec<f64>>> {
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

pub fn on_mult_line_flat(a: &Vec<f64>, b: &Vec<f64>) -> Option<Vec<f64>> {
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
